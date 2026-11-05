//go:build integration

package app

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
)

// Exercise actual HTTP, registered builtin tools and PostgreSQL without a model.
type commandFixture struct {
	t      *testing.T
	f      *Foundation
	server *httptest.Server
	client *http.Client
}

func newCommandFixture(t *testing.T) *commandFixture {
	t.Helper()
	var calls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		http.Error(w, "direct commands must not invoke a model", http.StatusInternalServerError)
	}))
	t.Cleanup(upstream.Close)
	t.Cleanup(func() {
		if calls.Load() != 0 {
			t.Errorf("direct command invoked model %d times", calls.Load())
		}
	})
	f := p7Foundation(t, map[string]string{"spring.ai.openai.base-url": upstream.URL})
	server := httptest.NewServer(f.Handler())
	t.Cleanup(server.Close)
	return &commandFixture{t: t, f: f, server: server, client: &http.Client{Timeout: 15 * time.Second}}
}

func (c *commandFixture) request(method, path, key string, body any, expected int) map[string]any {
	c.t.Helper()
	var raw []byte
	if body != nil {
		var err error
		raw, err = json.Marshal(body)
		if err != nil {
			c.t.Fatal(err)
		}
	}
	r, err := http.NewRequest(method, c.server.URL+"/api/v1"+path, bytes.NewReader(raw))
	if err != nil {
		c.t.Fatal(err)
	}
	r.Header.Set("Accept", "application/json")
	r.Header.Set("Content-Type", "application/json")
	if key != "" {
		r.Header.Set("Idempotency-Key", key)
	}
	response, err := c.client.Do(r)
	if err != nil {
		c.t.Fatal(err)
	}
	defer response.Body.Close()
	raw, err = io.ReadAll(response.Body)
	if err != nil {
		c.t.Fatal(err)
	}
	if response.StatusCode != expected {
		c.t.Fatalf("%s %s: want %d, got %d: %s", method, path, expected, response.StatusCode, raw)
	}
	var envelope map[string]any
	if err := json.Unmarshal(raw, &envelope); err != nil {
		c.t.Fatalf("invalid JSON: %s: %v", raw, err)
	}
	return envelope
}

func (c *commandFixture) session() string {
	c.t.Helper()
	return c.request("POST", "/chat/sessions", "", map[string]any{"title": "Direct command fixture"}, 201)["data"].(map[string]any)["conversationId"].(string)
}

func (c *commandFixture) command(conversation, key, tool string, args any, expected int) map[string]any {
	c.t.Helper()
	envelope := c.request("POST", "/chat/sessions/"+conversation+"/tools", key, map[string]any{"tool": tool, "arguments": args}, expected)
	if expected != 200 {
		return nil
	}
	return envelope["data"].(map[string]any)
}

func TestDirectToolCreationReplaysAndRejectsChangedPayloadPostgres(t *testing.T) {
	c := newCommandFixture(t)
	catalog, _ := json.Marshal(c.request("GET", "/chat/tools", "", nil, 200)["data"])
	if !bytes.Contains(catalog, []byte("createTask")) || !bytes.Contains(catalog, []byte("inputSchema")) || bytes.Contains(catalog, []byte("askUser")) {
		t.Fatal("unexpected catalog", string(catalog))
	}
	conversation, key := c.session(), agent.UUID()
	args := map[string]any{"title": "direct-" + key}
	first := c.command(conversation, key, "createTask", args, 200)
	result := first["result"].(map[string]any)
	if first["replayed"] != false || result["status"] != "OK" || result["data"].(map[string]any)["id"] == "" {
		t.Fatal(first)
	}
	replay := c.command(conversation, strings.ToUpper(key), "createTask", args, 200)
	originalJSON, _ := json.Marshal(first["result"])
	replayJSON, _ := json.Marshal(replay["result"])
	if replay["replayed"] != true || !bytes.Equal(originalJSON, replayJSON) || p7Count(t, c.f, "task") != 1 {
		t.Fatal("repeated command created a task or changed its receipt", replay)
	}
	c.command(conversation, key, "createTask", map[string]any{"title": "different"}, 409)
	c.command(c.session(), key, "createTask", args, 409)
	if p7Count(t, c.f, "task") != 1 {
		t.Fatal("conflicting key executed")
	}
	c.command(conversation, agent.UUID(), "createTask", args, 200)
	c.command(conversation, "", "createTask", args, 400)
	c.command(agent.UUID(), agent.UUID(), "createTask", args, 404)
	if p7Count(t, c.f, "task") != 2 {
		t.Fatal("new identity must create exactly one new task")
	}
}

func TestDirectToolDeletePreservesConfirmationAndOldReceiptPostgres(t *testing.T) {
	c := newCommandFixture(t)
	conversation, createKey := c.session(), agent.UUID()
	createArgs := map[string]any{"title": "delete-" + createKey}
	created := c.command(conversation, createKey, "createTask", createArgs, 200)
	taskID := created["result"].(map[string]any)["data"].(map[string]any)["id"].(string)
	deleteKey := agent.UUID()
	args := map[string]any{"taskIds": []string{taskID}}
	first := c.command(conversation, deleteKey, "deleteTask", args, 200)["result"].(map[string]any)
	if first["status"] != "REQUIRES_CONFIRMATION" {
		t.Fatal(first)
	}
	id := first["confirmationId"].(string)
	replay := c.command(conversation, deleteKey, "deleteTask", args, 200)
	if replay["replayed"] != true || replay["result"].(map[string]any)["confirmationId"] != id || p7Count(t, c.f, "task") != 1 {
		t.Fatal("delete replay changed confirmation or deleted before approval", replay)
	}
	pending := c.request("GET", "/agent/confirmations?status=PENDING&conversationId="+conversation, "", nil, 200)["data"].([]any)
	if len(pending) != 1 || pending[0].(map[string]any)["id"] != id {
		t.Fatal(pending)
	}
	decision := map[string]any{"token": pending[0].(map[string]any)["token"]}
	approved := c.request("POST", "/agent/confirmations/"+id+"/confirm", "", decision, 200)["data"].(map[string]any)
	if approved["executed"] != true || p7Count(t, c.f, "task") != 0 {
		t.Fatal("approval did not execute deletion", approved)
	}
	c.request("POST", "/agent/confirmations/"+id+"/confirm", "", decision, 409)
	c.command(conversation, deleteKey, "deleteTask", args, 200)
	c.command(conversation, createKey, "createTask", createArgs, 200)
	pending = c.request("GET", "/agent/confirmations?status=PENDING&conversationId="+conversation, "", nil, 200)["data"].([]any)
	if p7Count(t, c.f, "task") != 0 || len(pending) != 0 {
		t.Fatal("replayed receipt recreated a task or pending confirmation", pending)
	}
}

func TestDirectToolBusySessionDoesNotTakeOwnershipPostgres(t *testing.T) {
	c := newCommandFixture(t)
	conversation, key := c.session(), agent.UUID()
	args := map[string]any{"title": "busy-" + key}
	lease, err := c.f.coordinator.Acquire(context.Background(), "chat-session:"+conversation, 1)
	if err != nil || lease == nil {
		t.Fatal("cannot acquire fixture lease", err)
	}
	t.Cleanup(func() { _ = lease.Release(context.Background()) })
	c.command(conversation, key, "createTask", args, 409)
	if p7Count(t, c.f, "task") != 0 {
		t.Fatal("busy session executed command")
	}
	if err := lease.Release(context.Background()); err != nil {
		t.Fatal(err)
	}
	c.command(conversation, key, "createTask", args, 200)
	if p7Count(t, c.f, "task") != 1 {
		t.Fatal("busy attempt took ownership and prevented a safe retry")
	}
}
