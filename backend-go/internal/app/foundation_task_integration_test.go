//go:build integration

package app

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/task"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func TestFoundationP2TaskRoutesAndIdempotency(t *testing.T) {
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.rate-limit.enabled": "false"}})
	if err != nil {
		t.Fatal(err)
	}
	f, err := OpenFoundation(context.Background(), properties, FoundationOptions{Connector: fixtureAppConnector(t, "go-p2-app"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if err := f.Close(ctx); err != nil {
			t.Error(err)
		}
	}()
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	client := server.Client()
	request := func(method, path, body, key string) (int, map[string]any) {
		t.Helper()
		req, _ := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		if key != "" {
			req.Header.Set("Idempotency-Key", key)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var payload map[string]any
		if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
			t.Fatal(err)
		}
		return resp.StatusCode, payload
	}
	status, first := request("POST", "/api/v1/tasks", `{"title":"P2 任务","tags":[" 工作 ","学习"]}`, "p2-create-001")
	if status != 201 {
		t.Fatalf("create=%d %+v", status, first)
	}
	id := first["data"].(map[string]any)["id"].(string)
	status, replay := request("POST", "/api/v1/tasks", `{"title":"P2 任务","tags":[" 工作 ","学习"]}`, "p2-create-001")
	if status != 201 || replay["data"].(map[string]any)["id"] != id {
		t.Fatalf("replay=%d %+v", status, replay)
	}
	if code, body := request("POST", "/api/v1/tasks", `{"title":"不同任务"}`, "p2-create-001"); code != 409 || body["code"] != float64(40001) {
		t.Fatalf("digest conflict=%d %+v", code, body)
	}
	if code, _ := request("POST", "/api/v1/tasks", `{"title":"空格"}`, "short"); code != 400 {
		t.Fatalf("invalid key=%d", code)
	}
	if code, _ := request("POST", "/api/v1/tasks", `{"title":"   "}`, ""); code != 400 {
		t.Fatalf("blank title=%d", code)
	}
	if code, _ := request("GET", "/api/v1/tasks?status=INVALID", "", ""); code != 400 {
		t.Fatalf("invalid status=%d", code)
	}
	if code, body := request("GET", "/api/v1/tasks?tag=%E5%B7%A5%E4%BD%9C", "", ""); code != 200 || body["data"].(map[string]any)["total"] != float64(1) {
		t.Fatalf("tag filter=%d %+v", code, body)
	}
	defer request("DELETE", "/api/v1/tasks/"+id, "", "")
	for _, path := range []string{"/api/v1/tasks", "/api/v1/tasks/tags", "/api/v1/tasks/stats", "/api/v1/tasks/" + id} {
		code, body := request("GET", path, "", "")
		if code != 200 {
			t.Fatalf("GET %s=%d", path, code)
		}
		if path == "/api/v1/tasks/stats" {
			data := body["data"].(map[string]any)
			if _, ok := data["pending"]; !ok {
				t.Fatalf("stats shape=%+v", data)
			}
		}
	}
	if code, _ := request("PATCH", "/api/v1/tasks/"+id, `{"status":"DOING","tags":[]}`, "p2-patch-001"); code != 200 {
		t.Fatalf("patch=%d", code)
	}
	if code, _ := request("POST", "/api/v1/tasks/"+id+"/complete", "", ""); code != 200 {
		t.Fatalf("complete=%d", code)
	}
	if code, result := request("POST", "/api/v1/tasks/"+id+"/reopen", "", ""); code != 200 || result["data"].(map[string]any)["status"] != "DOING" {
		t.Fatalf("reopen=%d %+v", code, result)
	}
	if code, _ := request("DELETE", "/api/v1/tasks/"+id, "", "p2-delete-001"); code != 200 {
		t.Fatalf("delete=%d", code)
	}
	if code, _ := request("DELETE", "/api/v1/tasks/"+id, "", "p2-delete-001"); code != 200 {
		t.Fatalf("delete replay=%d", code)
	}
}
func TestFoundationP2ConfirmationAndCrossConnectionIdempotency(t *testing.T) {
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.rate-limit.enabled": "false"}})
	if err != nil {
		t.Fatal(err)
	}
	open := func() *Foundation {
		f, err := OpenFoundation(context.Background(), properties, FoundationOptions{Connector: fixtureAppConnector(t, "go-p2-cross"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
		if err != nil {
			t.Fatal(err)
		}
		return f
	}
	first, second := open(), open()
	defer func() {
		for _, f := range []*Foundation{first, second} {
			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			if err := f.Close(ctx); err != nil {
				t.Error(err)
			}
			cancel()
		}
	}()
	a, b := httptest.NewServer(first.Handler()), httptest.NewServer(second.Handler())
	defer a.Close()
	defer b.Close()
	key := "p2-cross-task-" + time.Now().UTC().Format("150405.000000")
	request := func(url, method, path, body, k string) (int, map[string]any) {
		req, _ := http.NewRequest(method, url+path, strings.NewReader(body))
		if body != "" {
			req.Header.Set("Content-Type", "application/json")
		}
		if k != "" {
			req.Header.Set("Idempotency-Key", k)
		}
		response, err := a.Client().Do(req)
		if err != nil {
			t.Error(err)
			return 0, nil
		}
		defer response.Body.Close()
		var result map[string]any
		if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
			t.Error(err)
		}
		return response.StatusCode, result
	}
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			url := a.URL
			if i%2 == 1 {
				url = b.URL
			}
			code, _ := request(url, "POST", "/api/v1/tasks", `{"title":"跨连接单次创建"}`, key)
			if code != 201 && code != 409 {
				t.Errorf("race status=%d", code)
			}
		}(i)
	}
	wg.Wait()
	code, replayed := request(b.URL, "POST", "/api/v1/tasks", `{"title":"跨连接单次创建"}`, key)
	if code != 201 {
		t.Fatalf("replay=%d %+v", code, replayed)
	}
	id := replayed["data"].(map[string]any)["id"].(string)
	store, _ := task.NewPostgresStore(first.Pool())
	tasks, _ := task.NewService(store)
	page, err := tasks.Search(context.Background(), task.Query{Keyword: "跨连接单次创建", Size: 100})
	if err != nil || page.Total != 1 {
		t.Fatalf("cross connection task count=%d err=%v", page.Total, err)
	}
	confirmStore, _ := confirmation.NewPostgresStore(first.Pool())
	handler, _ := task.NewConfirmationHandler(tasks)
	registry, _ := confirmation.NewRegistryFor([]confirmation.Action{confirmation.DeleteTask, confirmation.ScheduleTask, confirmation.SetPriority}, handler)
	service, _ := confirmation.NewService(confirmStore, registry)
	requests, _ := task.NewConfirmationRequests(tasks, service)
	pending, err := requests.Delete(context.Background(), []string{id}, "p2-conversation")
	if err != nil {
		t.Fatal(err)
	}
	code, listing := request(a.URL, "GET", "/api/v1/agent/confirmations?conversationId=p2-conversation", "", "")
	if code != 200 || len(listing["data"].([]any)) != 1 {
		t.Fatalf("list=%d %+v", code, listing)
	}
	payload, _ := json.Marshal(map[string]string{"token": pending.Token})
	code, result := request(b.URL, "POST", "/api/v1/agent/confirmations/"+pending.ID+"/confirm", string(payload), "")
	if code != 200 || result["data"].(map[string]any)["affectedCount"] != float64(1) {
		t.Fatalf("confirm=%d %+v", code, result)
	}
	if _, err := tasks.Get(context.Background(), id); err != task.ErrNotFound {
		t.Fatalf("task still exists: %v", err)
	}
	code, _ = request(a.URL, "POST", "/api/v1/agent/confirmations/"+pending.ID+"/confirm", string(payload), "")
	if code != 409 {
		t.Fatalf("repeat confirm=%d", code)
	}
	cancelTask, err := tasks.Create(context.Background(), task.CreateRequest{Title: "取消确认保留任务"})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = tasks.Delete(context.Background(), cancelTask.ID) }()
	cancellable, err := requests.Priority(context.Background(), cancelTask.ID, task.High, "p2-conversation")
	if err != nil {
		t.Fatal(err)
	}
	cancelBody, _ := json.Marshal(map[string]string{"token": cancellable.Token})
	code, _ = request(b.URL, "POST", "/api/v1/agent/confirmations/"+cancellable.ID+"/cancel", string(cancelBody), "")
	if code != 200 {
		t.Fatalf("cancel=%d", code)
	}
	if current, err := tasks.Get(context.Background(), cancelTask.ID); err != nil || current.Priority != task.Medium {
		t.Fatalf("cancel changed task=%+v err=%v", current, err)
	}
	code, _ = request(a.URL, "POST", "/api/v1/agent/confirmations/"+cancellable.ID+"/confirm", string(cancelBody), "")
	if code != 409 {
		t.Fatalf("confirm cancelled=%d", code)
	}
}
