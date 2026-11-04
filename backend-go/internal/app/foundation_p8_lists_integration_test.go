//go:build integration

package app

import (
	"context"
	"fmt"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/chat"
)

func TestP8SessionAndDocumentListSearchContracts(t *testing.T) {
	var calls atomic.Int32
	f := p6Foundation(t, "api", p6Upstream(t, &calls), nil, nil)
	ctx := context.Background()
	prefix := fmt.Sprintf("p8-list-%d-", time.Now().UnixNano())
	sessions, documents := map[string]bool{}, map[string]bool{}
	var active string
	for i := range 25 {
		title := fmt.Sprintf("%stitle-%02d", prefix, i)
		session, err := f.chat.Service.Create(ctx, &title)
		if err != nil {
			t.Fatal(err)
		}
		sessions[session.ConversationID] = true
		active = session.ConversationID
		t.Cleanup(func() { _ = f.chat.Service.Delete(context.Background(), session.ConversationID) })
		document, err := f.p6.Store.Ingest(ctx, fmt.Sprintf("%sfile-%02d.md", prefix, i), title, []byte(strings.Repeat("持久数据库文档与列表分页回归证据。", 30)))
		if err != nil {
			t.Fatal(err)
		}
		documents[document.ID] = true
		t.Cleanup(func() { _, _ = f.p6.Store.Delete(context.Background(), document.ID) })
	}
	if err := f.chat.Service.Append(ctx, active, []chat.NewMessage{{Role: "USER", Content: "first"}, {Role: "ASSISTANT", Content: "second"}}); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(f.Handler())
	t.Cleanup(server.Close)
	get := func(path string) any {
		t.Helper()
		code, body := appRequest(t, server.Client(), "GET", server.URL+"/api/v1"+path, "", "")
		if code != 200 {
			t.Fatalf("GET %s=%d", path, code)
		}
		return body["data"]
	}
	for _, module := range []struct {
		path, id string
		own      map[string]bool
	}{
		{"/chat/sessions", "conversationId", sessions}, {"/knowledge/documents", "id", documents},
	} {
		seen := map[string]bool{}
		for page := range 2 {
			result := get(module.path + "/search?keyword=" + url.QueryEscape(prefix) + fmt.Sprintf("&page=%d&size=20", page)).(map[string]any)
			items := result["items"].([]any)
			want := 20
			if page == 1 {
				want = 5
			}
			if result["total"] != float64(25) || len(items) != want {
				t.Fatal("page total/size", module.path, result)
			}
			for _, raw := range items {
				item := raw.(map[string]any)
				id := item[module.id].(string)
				if !module.own[id] || seen[id] {
					t.Fatal("page duplicates/foreign ID", module.path)
				}
				seen[id] = true
				if id == active && (page != 0 || item["messageCount"] != float64(2)) {
					t.Fatal("paged message count/activity order")
				}
			}
		}
		if len(seen) != 25 {
			t.Fatal("page omitted IDs")
		}
		for _, test := range []struct {
			query string
			size  int
			items int
		}{
			{"size=1000", 100, 25}, {"size=0", 20, 20}, {"page=-1&size=20", 20, 20}, {"page=999&size=20", 20, 0}, {"page=2147483647&size=100", 100, 0},
		} {
			result := get(module.path + "/search?keyword=" + url.QueryEscape(prefix) + "&" + test.query).(map[string]any)
			if result["size"] != float64(test.size) || result["total"] != float64(25) || len(result["items"].([]any)) != test.items {
				t.Fatal("clamp/overflow", module.path, test.query)
			}
		}
		miss := get(module.path + "/search?keyword=" + url.QueryEscape(prefix+"absent")).(map[string]any)
		if miss["total"] != float64(0) || len(miss["items"].([]any)) != 0 {
			t.Fatal("search miss")
		}
		all := get(module.path).([]any)
		count := 0
		for _, raw := range all {
			if module.own[raw.(map[string]any)[module.id].(string)] {
				count++
			}
		}
		if count != 25 {
			t.Fatal("full endpoint was paginated")
		}
	}
	for _, keyword := range []string{prefix + "title-07", prefix + "file-07"} {
		result := get("/knowledge/documents/search?keyword=" + url.QueryEscape(keyword)).(map[string]any)
		if result["total"] != float64(1) {
			t.Fatal("title/filename search", keyword)
		}
	}
	hit := get("/chat/sessions/search?keyword=" + url.QueryEscape(prefix+"title-07")).(map[string]any)
	if hit["total"] != float64(1) {
		t.Fatal("session title search")
	}
}
