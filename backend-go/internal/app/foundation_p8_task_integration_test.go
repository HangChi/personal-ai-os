//go:build integration

package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/task"
)

func TestP8TaskHTTPNoKeyMissingAndTagValidation(t *testing.T) {
	var calls atomic.Int32
	f := p6Foundation(t, "api", p6Upstream(t, &calls), nil, nil)
	store, err := task.NewPostgresStore(f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	service, err := task.NewService(store)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(f.Handler())
	t.Cleanup(server.Close)
	prefix := fmt.Sprintf("p8-http-%d", time.Now().UnixNano())
	ids := []string{}
	for range 2 {
		body, _ := json.Marshal(map[string]any{"title": prefix})
		status, result := appRequest(t, server.Client(), "POST", server.URL+"/api/v1/tasks", "", string(body))
		if status != 201 {
			t.Fatal("create without key", status, result)
		}
		id := result["data"].(map[string]any)["id"].(string)
		ids = append(ids, id)
		t.Cleanup(func() { _ = service.Delete(context.Background(), id) })
		if row, err := service.Get(context.Background(), id); err != nil || row.Title != prefix {
			t.Fatal("HTTP create did not persist", err)
		}
	}
	page, err := service.Search(context.Background(), task.Query{Keyword: prefix})
	if err != nil || ids[0] == ids[1] || page.Total != 2 {
		t.Fatal("missing key must not deduplicate", page.Total, err)
	}
	status, result := appRequest(t, server.Client(), "PATCH", server.URL+"/api/v1/tasks/"+ids[1], "", `{"status":"DOING","priority":"HIGH"}`)
	if status != 200 {
		t.Fatal("filter setup", status, result)
	}
	status, result = appRequest(t, server.Client(), "GET", server.URL+"/api/v1/tasks?keyword="+prefix+"&status=DOING&priority=HIGH", "", "")
	if status != 200 {
		t.Fatal("filter request", status, result)
	}
	filtered := result["data"].(map[string]any)
	items := filtered["items"].([]any)
	if filtered["total"] != float64(1) || len(items) != 1 || items[0].(map[string]any)["id"] != ids[1] {
		t.Fatal("status/priority/keyword must compose", filtered)
	}
	for _, tags := range [][]string{{"a", "b", "c", "d", "e", "f"}, {strings.Repeat("字", 21)}} {
		body, _ := json.Marshal(map[string]any{"title": prefix, "tags": tags})
		status, result := appRequest(t, server.Client(), "POST", server.URL+"/api/v1/tasks", "", string(body))
		if status != 400 || result["code"] != float64(40000) {
			t.Fatal("invalid tag HTTP boundary", status, result)
		}
	}
	page, err = service.Search(context.Background(), task.Query{Keyword: prefix})
	if err != nil || page.Total != 2 {
		t.Fatal("invalid tags caused a partial write", err)
	}
	status, result = appRequest(t, server.Client(), "DELETE", server.URL+"/api/v1/tasks/"+ids[0], "", "")
	if status != 200 || result["data"].(map[string]any)["deleted"] != true {
		t.Fatal("human deletion not immediate", status, result)
	}
	for _, id := range []string{ids[0], "00000000-0000-0000-0000-000000000000"} {
		status, result := appRequest(t, server.Client(), "GET", server.URL+"/api/v1/tasks/"+id, "", "")
		if status != 404 || result["code"] != float64(40004) {
			t.Fatal("missing task envelope", status, result)
		}
	}
}
