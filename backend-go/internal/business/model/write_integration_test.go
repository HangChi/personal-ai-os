//go:build integration

package model

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

func writeFixture(t *testing.T) (*Service, *PostgresReadStore) {
	t.Helper()
	pool := fixturePool(t)
	ctx := context.Background()
	clean := func() {
		_ = pool.WithConn(ctx, func(c *sql.Conn) error {
			_, e := c.ExecContext(ctx, `DELETE FROM ai_model_profile; DELETE FROM ai_model_config`)
			return e
		})
	}
	clean()
	t.Cleanup(clean)
	store, e := NewPostgresReadStore(pool, fixtureKey(t, 1))
	if e != nil {
		t.Fatal(e)
	}
	s, e := NewService(store, resolver(t, nil, map[string]string{"OPENAI_API_KEY": "synthetic-env-fallback-abcd"}), NewRuntime(nil))
	if e != nil {
		t.Fatal(e)
	}
	s.runtime.Register(s.ResolveNamed)
	return s, store
}
func profileCommand(name string) ProfileCommand {
	return ProfileCommand{Name: &name, SaveCommand: SaveCommand{BaseURL: ptr("https://fixture.invalid/v1"), APIKey: ptr("synthetic-saved-key-wxyz"), ChatModel: ptr("legacy-chat"), EmbeddingModel: ptr("legacy-embed")}, ChatModels: []*string{ptr(" gpt-4o "), nil, ptr("gpt-4o"), ptr("custom")}, EmbeddingModels: []*string{ptr("bge-m3")}, AudioModels: []*string{ptr("whisper-1")}, ContextWindows: map[string]*int{"gpt-4o": intPointer(4096)}}
}
func TestP3TransactionalWritesKeysActivationAndRuntime(t *testing.T) {
	s, store := writeFixture(t)
	ctx := context.Background()
	first, e := s.Save(ctx, SaveCommand{ChatModel: ptr("legacy"), APIKey: ptr("synthetic-legacy-first-1234")})
	if e != nil || *first.Revision != 1 {
		t.Fatal(first, e)
	}
	_, e = s.Save(ctx, SaveCommand{ChatModel: ptr("preserved")})
	if e != nil {
		t.Fatal(e)
	}
	v, _, _ := store.Legacy(ctx)
	if value(v.APIKey) != "synthetic-legacy-first-1234" || *v.Revision != 2 {
		t.Fatal("null key retention failed")
	}
	_, e = s.Save(ctx, SaveCommand{APIKey: ptr(" \t ")})
	if e != nil {
		t.Fatal(e)
	}
	v, _, _ = store.Legacy(ctx)
	if v.APIKey != nil || *v.Revision != 3 {
		t.Fatal("blank key did not clear")
	}
	c := profileCommand("first")
	a, e := s.Create(ctx, c)
	if e != nil || a.Active || a.SortOrder != 0 || a.Revision != 1 || len(a.ChatModels) != 2 || a.ChatModel != "gpt-4o" || a.ContextWindows["gpt-4o"] != 4096 {
		t.Fatalf("create=%+v %v", a, e)
	}
	b, e := s.Create(ctx, profileCommand("second"))
	if e != nil || b.SortOrder != 1 {
		t.Fatal(b, e)
	}
	if _, e = s.Activate(ctx, a.ID); e != nil {
		t.Fatal(e)
	}
	before, _ := store.Require(ctx, a.ID)
	if _, e = s.Activate(ctx, a.ID); e != nil {
		t.Fatal(e)
	}
	after, _ := store.Require(ctx, a.ID)
	if !before.UpdatedAt.Equal(*after.UpdatedAt) || *before.Revision != *after.Revision {
		t.Fatal("repeat activation changed version/time")
	}
	current := s.runtime.Current()
	c.Name = ptr("second changed")
	c.APIKey = nil
	c.ChatModels = []*string{ptr("qwen-max")}
	updated, e := s.Update(ctx, b.ID, c)
	if e != nil || updated.ChatModel != "qwen-max" || updated.ContextWindows["qwen-max"] != 30720 || s.runtime.Current() != current {
		t.Fatal("inactive update affected runtime", e)
	}
	stored, _ := store.Require(ctx, b.ID)
	if value(stored.APIKey) != "synthetic-saved-key-wxyz" {
		t.Fatal("profile nil key changed secret")
	}
	var cipher string
	_ = store.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT api_key FROM ai_model_profile WHERE id=$1`, b.ID).Scan(&cipher)
	})
	if !strings.HasPrefix(cipher, keyPrefix) || strings.Contains(cipher, "synthetic") {
		t.Fatal("key not encrypted at rest")
	}
	if e = s.Delete(ctx, a.ID); e == nil {
		t.Fatal("deleted active")
	}
	oldTimes := map[string]time.Time{}
	for _, id := range []string{a.ID, b.ID} {
		p, _ := store.Require(ctx, id)
		oldTimes[id] = *p.UpdatedAt
	}
	_, e = s.Reorder(ctx, []string{b.ID, b.ID, "00000000-0000-0000-0000-000000000000"})
	if e != nil {
		t.Fatal(e)
	}
	ps, _ := store.Profiles(ctx)
	if ps[0].ID != b.ID || ps[1].ID != a.ID {
		t.Fatal("unstable reorder")
	}
	for _, p := range ps {
		if !oldTimes[p.ID].Equal(*p.UpdatedAt) {
			t.Fatal("reorder changed timestamp")
		}
	}
	// 保存已经提交，运行时工厂失败不允许回滚数据库或替换旧引用。
	s.runtime.factory = func(Resolved) (*ChatClient, error) { return nil, errors.New("synthetic-secret-never-exposed") }
	c.Name = ptr("persisted despite failure")
	_, e = s.Update(ctx, a.ID, c)
	var business *api.BusinessError
	if !errors.As(e, &business) || business.Code() != api.AIUpstreamError || strings.Contains(e.Error(), "secret") {
		t.Fatal("apply failure semantics", e)
	}
	saved, _ := store.Require(ctx, a.ID)
	if saved.Name != "persisted despite failure" || *saved.Revision != 2 || s.runtime.Current() != current {
		t.Fatal("apply failure rolled back saved config")
	}
	if e = s.Delete(ctx, b.ID); e != nil {
		t.Fatal(e)
	}
	_ = store.pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, e := conn.ExecContext(ctx, `UPDATE ai_model_profile SET active=false WHERE id=$1`, a.ID)
		return e
	})
	if e = s.Delete(ctx, a.ID); e == nil {
		t.Fatal("deleted last inactive")
	}
	p, e := store.Rename(ctx, a.ID, "renamed")
	if e != nil || *p.Revision != 2 {
		t.Fatal("rename bumped revision")
	}
}
func TestP3ConcurrentActivationAndReplaceRollback(t *testing.T) {
	s, store := writeFixture(t)
	ctx := context.Background()
	ids := []string{}
	for i := 0; i < 4; i++ {
		p, e := s.Create(ctx, profileCommand(fmt.Sprint(i)))
		if e != nil {
			t.Fatal(e)
		}
		ids = append(ids, p.ID)
	}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func(id string) {
			defer wg.Done()
			if _, e := store.Activate(ctx, id); e != nil {
				t.Error(e)
			}
		}(ids[i%len(ids)])
	}
	wg.Wait()
	ps, _ := store.Profiles(ctx)
	active := 0
	for _, p := range ps {
		if p.Active {
			active++
		}
	}
	if active != 1 {
		t.Fatal("active uniqueness lost")
	}
	before, _ := store.Require(ctx, ids[0])
	c := profileCommand("rollback")
	c.ChatModels = []*string{ptr("custom")}
	c.ContextWindows = map[string]*int{"custom": intPointer(-1)}
	_, e := s.Update(ctx, ids[0], c)
	if e != nil {
		t.Fatal("invalid manual window should fallback", e)
	}
	p, _ := store.Require(ctx, ids[0])
	if p.Models[1].ContextWindow != nil && p.Models[1].Name == "custom" {
		t.Fatal("invented unknown window")
	}
	_ = before
	c.ChatModels = []*string{ptr("oversized")}
	c.ContextWindows = map[string]*int{"oversized": intPointer(2147483647)}
	if _, e = store.Update(ctx, "00000000-0000-0000-0000-000000000000", c); e == nil {
		t.Fatal("updated missing profile")
	}
	cancelCtx, cancel := context.WithCancel(ctx)
	cancel()
	if _, e = store.Activate(cancelCtx, ids[0]); !errors.Is(e, context.Canceled) {
		t.Fatal("cancellation lost", e)
	}
}
func TestP3AllModelRoutesOnOriginalSchema(t *testing.T) {
	s, store := writeFixture(t)
	var upstreamPathsMu sync.Mutex
	paths := map[string]int{}
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		upstreamPathsMu.Lock()
		paths[r.URL.Path]++
		upstreamPathsMu.Unlock()
		switch r.URL.Path {
		case "/v1/chat/completions":
			fmt.Fprint(w, `{"choices":[{"message":{"content":"正常"}}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}`)
		case "/v1/models":
			fmt.Fprint(w, `{"data":[{"id":"fixture-chat","context_length":8192}]}`)
		case "/v1/models/fixture-chat":
			fmt.Fprint(w, `{"context_length":8192}`)
		case "/v1/audio/transcriptions":
			if e := r.ParseMultipartForm(1 << 20); e != nil {
				t.Error(e)
			}
			file, header, e := r.FormFile("file")
			if e != nil {
				t.Error(e)
				return
			}
			defer file.Close()
			data, _ := io.ReadAll(file)
			if string(data) != "synthetic-audio" || header.Filename != "clip.webm" || r.FormValue("model") != "whisper-1" {
				t.Error("multipart wire mismatch")
			}
			fmt.Fprint(w, `{"data":{"text":"  测试语音  "}}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	transport := upstream.Client().Transport
	s.runtime.factory = func(v Resolved) (*ChatClient, error) { return NewChatClient(v, OpenAI, transport) }
	handler := web.MultipartLifecycle(NewHTTP(s, NewProbe(transport), NewAudioService(s.ReadService, transport), slog.New(slog.NewTextHandler(io.Discard, nil))), web.MultipartLimits{MaxFileBytes: MaxAudioBytes, MaxRequestBytes: 10 << 20})
	request := func(method, path, body string) (int, map[string]any) {
		t.Helper()
		req := httptest.NewRequest(method, path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, req)
		var out map[string]any
		if e := json.Unmarshal(w.Body.Bytes(), &out); e != nil {
			t.Fatalf("%s %s body=%s", method, path, w.Body.String())
		}
		if strings.Contains(w.Body.String(), "synthetic-saved-key") {
			t.Fatal("response leaked key")
		}
		return w.Code, out
	}
	if code, _ := request("GET", modelPath, ""); code != 200 {
		t.Fatal(code)
	}
	saved := fmt.Sprintf(`{"baseUrl":%q,"apiKey":"synthetic-saved-key-wxyz","chatModel":"fixture-chat"}`, upstream.URL)
	if code, _ := request("PUT", modelPath, saved); code != 200 {
		t.Fatal(code)
	}
	profile := fmt.Sprintf(`{"name":"first","baseUrl":%q,"apiKey":"synthetic-saved-key-wxyz","chatModels":["fixture-chat"],"audioModels":["whisper-1"]}`, upstream.URL)
	code, out := request("POST", modelPath+"/profiles", profile)
	if code != 200 {
		t.Fatal(code, out)
	}
	id := out["data"].(map[string]any)["id"].(string)
	for _, route := range []struct{ method, path, body string }{{"GET", modelPath + "/profiles", ""}, {"PUT", modelPath + "/profiles/" + id, profile}, {"POST", modelPath + "/profiles/" + id + "/activate", ""}, {"POST", modelPath + "/test", ""}, {"POST", modelPath + "/profiles/test", ""}, {"PUT", modelPath + "/profiles/order", fmt.Sprintf(`{"ids":[%q]}`, id)}, {"POST", modelPath + "/profiles/probe-models", fmt.Sprintf(`{"profileId":%q}`, id)}, {"POST", modelPath + "/profiles/probe-window", fmt.Sprintf(`{"profileId":%q,"model":"fixture-chat"}`, id)}, {"GET", "/api/v1/chat/transcribe/status", ""}} {
		if code, out := request(route.method, route.path, route.body); code != 200 {
			t.Fatal(route, code, out)
		}
	}
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	file, _ := writer.CreateFormFile("file", "clip.webm")
	_, _ = file.Write([]byte("synthetic-audio"))
	_ = writer.Close()
	req := httptest.NewRequest("POST", "/api/v1/chat/transcribe", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "测试语音") {
		t.Fatal("transcribe", w.Code, w.Body.String())
	}
	for _, bad := range []string{`null`, `[]`, `{"name":"x","chatModels":{}}`, `{"name":"x","temperature":"bad"}`, `{"name":"x","apiKey":{}}`, `{"name":"x","contextWindows":{"x":true}}`, `{"Name":"case"}`, `{"name":"  "}`, `{"name":"x","baseUrl":"file:///etc"}`, `{"name":"x","temperature":3}`, `{"name":"x"} {}`} {
		if code, _ := request("POST", modelPath+"/profiles", bad); code != 400 {
			t.Fatalf("binding %s=%d", bad, code)
		}
	}
	if code, _ := request("DELETE", modelPath+"/profiles/"+id, ""); code != 409 {
		t.Fatal("active delete", code)
	}
	p, e := s.Create(context.Background(), profileCommand("deletable"))
	if e != nil {
		t.Fatal(e)
	}
	if code, _ := request("DELETE", modelPath+"/profiles/"+p.ID, ""); code != 200 {
		t.Fatal("delete", code)
	}
	profiles, e := store.Profiles(context.Background())
	if e != nil || len(profiles) != 1 {
		t.Fatal("route writes missing", e)
	}
	upstreamPathsMu.Lock()
	defer upstreamPathsMu.Unlock()
	for _, path := range []string{"/v1/chat/completions", "/v1/models", "/v1/models/fixture-chat", "/v1/audio/transcriptions"} {
		if paths[path] == 0 {
			t.Error("no upstream call", path)
		}
	}
}
