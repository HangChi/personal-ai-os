//go:build integration

package app

import (
	"bufio"
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/config"
)

type memoryEmbedding struct{}

func (memoryEmbedding) Embed(_ context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	vector := make([]float32, 1024)
	vector[0] = 1
	return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Vector: vector}}}, nil
}

func TestP5MemoryPostgres(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	store, err := memory.NewStore(ctx, f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	store.UseEmbedding(memoryEmbedding{}, "fixture", 0.92)
	first, err := store.Create(ctx, "  每周五用中文提交报告  ")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Delete(ctx, first.ID)
	if _, err := store.Pin(ctx, first.ID, true); err != nil {
		t.Fatal(err)
	}
	same, err := store.CreateAuto(ctx, "每周五用中文提交报告")
	if err != nil || same.ID != first.ID || same.Source != "MANUAL" {
		t.Fatalf("duplicate = %+v, %v", same, err)
	}
	second, err := store.CreateAuto(ctx, "周五的报告需要用中文")
	if err != nil || !second.Pinned || second.Source != "AUTO" {
		t.Fatalf("replacement = %+v, %v", second, err)
	}
	settings := config.MemoryProperties{PinnedMaxCount: 10, Ranking: config.MemoryRanking{Enabled: true, Timeout: time.Second}}
	for range 2 {
		block, err := store.Prompt(ctx, "周报", settings)
		if err != nil || !strings.Contains(block, second.Content) || strings.Contains(block, first.Content) {
			t.Fatalf("prompt = %q, %v", block, err)
		}
	}
	all, err := store.List(ctx)
	if err != nil || len(all) != 2 || all[0].ID != second.ID || all[0].HitCount != 1 ||
		all[1].SupersededBy == nil || *all[1].SupersededBy != second.ID {
		t.Fatalf("list = %+v, %v", all, err)
	}
	if err := store.Delete(ctx, second.ID); err != nil {
		t.Fatal(err)
	}
	all, err = store.List(ctx)
	if err != nil || len(all) != 1 || all[0].SupersededBy != nil {
		t.Fatalf("delete = %+v, %v", all, err)
	}
}

func TestP5ReminderPostgres(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	settings, err := config.BindCalendarProperties(nil)
	if err != nil {
		t.Fatal(err)
	}
	events := calendar.NewEvents(f.Pool(), settings, time.UTC)
	now := time.Date(2026, 11, 4, 1, 0, 0, 0, time.UTC)
	start := now.Add(-time.Hour)
	title := "P5 missed reminder"
	event, err := events.Create(ctx, calendar.EventRequest{Title: &title, StartsAt: &start, EndsAt: &now})
	if err != nil {
		t.Fatal(err)
	}
	for i := range 2 {
		result, err := events.Scan(ctx, now)
		if err != nil || result.Created != int64(1-i) || result.Skipped != int64(i) {
			t.Fatalf("scan %d = %+v, %v", i, result, err)
		}
	}
	items, err := events.Reminders(ctx, "", true, 10, now)
	if err != nil || len(items) != 1 || items[0].SourceID != event.ID {
		t.Fatalf("due = %+v, %v", items, err)
	}
	for _, at := range []time.Time{now, now.Add(time.Minute)} {
		item, err := events.MarkRead(ctx, items[0].ID, at)
		if err != nil || item.Status != "READ" || item.ReadAt == nil || !item.ReadAt.Equal(now) {
			t.Fatalf("read = %+v, %v", item, err)
		}
	}
	if removed, err := events.Delete(ctx, event.ID); err != nil || removed != 1 {
		t.Fatalf("delete removed %d reminders, %v", removed, err)
	}
}

func TestP5ReminderRabbitFanout(t *testing.T) {
	uri := os.Getenv("GO_TEST_REMINDER_RABBIT_URI")
	if uri == "" {
		t.Skip("需要本次测试专用的 RabbitMQ")
	}
	parsed, err := url.Parse(uri)
	if err != nil || parsed.Scheme != "amqp" || parsed.Hostname() != "127.0.0.1" || parsed.Port() == "5672" {
		t.Fatal("RabbitMQ 测试仅接受随机回环端口")
	}
	first := fixtureFoundation(t, nil, nil)
	second := fixtureFoundation(t, nil, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	settings, _ := config.BindCalendarProperties(nil)
	source := calendar.NewEvents(first.Pool(), settings, time.UTC)
	reader := calendar.NewEvents(second.Pool(), settings, time.UTC)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	var snapshots []*bufio.Reader
	for _, events := range []*calendar.Events{source, reader} {
		stream := calendar.NewStream(ctx, events, time.Hour)
		defer stream.Close()
		bus, err := calendar.StartBroadcast(ctx, uri, events, stream, first.coordinator, events == source, logger)
		if err != nil {
			t.Fatal(err)
		}
		defer bus.Close()
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_ = stream.Serve(w, r)
		}))
		defer server.Close()
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, server.URL, nil)
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		snapshots = append(snapshots, bufio.NewReader(response.Body))
	}
	expect := func(needle string) {
		t.Helper()
		for _, stream := range snapshots {
			for {
				line, err := stream.ReadString('\n')
				if err != nil {
					t.Fatalf("waiting for %s: %v", needle, err)
				}
				if strings.HasPrefix(line, "data:") && strings.Contains(line, needle) {
					break
				}
			}
		}
	}
	expect("[]")
	now := time.Now().UTC().Truncate(time.Microsecond)
	title := "rabbit-fanout-fixture"
	event, err := source.Create(ctx, calendar.EventRequest{Title: &title, StartsAt: &now, EndsAt: &now})
	if err != nil {
		t.Fatal(err)
	}
	defer source.Delete(context.Background(), event.ID)
	if _, err := source.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	expect(title)
	due, err := reader.Reminders(ctx, "", true, 10, now)
	if err != nil || len(due) != 1 {
		t.Fatalf("due = %+v, %v", due, err)
	}
	if _, err := reader.MarkRead(ctx, due[0].ID, now); err != nil {
		t.Fatal(err)
	}
	expect("[]")
}

func TestP5WebhookRecovery(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	settings, _ := config.BindCalendarProperties(nil)
	events := calendar.NewEvents(f.Pool(), settings, time.UTC)
	const secret = "fixture-only-secret-at-least-32-characters"
	var mu sync.Mutex
	var ids []string
	redirected := false
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		if r.URL.Path == "/redirect" {
			redirected = true
			w.WriteHeader(http.StatusNoContent)
			return
		}
		body, err := io.ReadAll(r.Body)
		if err != nil {
			t.Error(err)
		}
		mac := hmac.New(sha256.New, []byte(secret))
		_, _ = mac.Write([]byte(r.Header.Get("X-PersonalOS-Timestamp") + "."))
		_, _ = mac.Write(body)
		if r.Header.Get("X-PersonalOS-Signature") != "sha256="+hex.EncodeToString(mac.Sum(nil)) {
			t.Error("webhook signature mismatch")
		}
		var payload map[string]string
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Error(err)
		}
		id := r.Header.Get("Idempotency-Key")
		if id == "" || id != payload["id"] || payload["event"] != "reminder.due" {
			t.Error("invalid webhook identity")
		}
		ids = append(ids, id)
		if len(ids) == 1 {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		http.Redirect(w, r, "/redirect", http.StatusTemporaryRedirect)
	}))
	defer server.Close()
	worker, err := calendar.NewWebhook(events, server.URL, secret, true)
	if err != nil {
		t.Fatal(err)
	}
	defer worker.Close()
	if err := worker.Dispatch(ctx); err != nil {
		t.Fatal(err)
	}
	appExec(t, f, `UPDATE reminder_webhook_target SET enabled_at=now()-interval '1 hour'`)
	now := time.Now().UTC().Truncate(time.Microsecond).Add(-time.Minute)
	title := "webhook fixture"
	lead := 0
	event, err := events.Create(ctx, calendar.EventRequest{Title: &title, StartsAt: &now, EndsAt: &now, Lead: &lead})
	if err != nil {
		t.Fatal(err)
	}
	defer events.Delete(ctx, event.ID)
	if _, err := events.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	if err := worker.Dispatch(ctx); err != nil {
		t.Fatal(err)
	}
	appExec(t, f, `UPDATE reminder_webhook_delivery SET state='SENDING',next_attempt_at=now()-interval '1 second' WHERE state='PENDING' AND attempts=1`)
	if err := worker.Dispatch(ctx); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if len(ids) != 2 || ids[0] != ids[1] || redirected {
		t.Fatalf("attempt count = %d, retry stable ID = %v, redirected = %v", len(ids), len(ids) == 2 && ids[0] == ids[1], redirected)
	}
	var state string
	err = f.Pool().WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT state FROM reminder_webhook_delivery WHERE id=$1`, ids[0]).Scan(&state)
	})
	if err != nil || state != "FAILED" {
		t.Fatalf("webhook state = %s, %v", state, err)
	}
}

func TestP5BrowserWebPush(t *testing.T) {
	path := os.Getenv("GO_TEST_REMINDER_BROWSER_SUBSCRIPTION")
	if path == "" {
		t.Skip("需要本次 Chrome 生成的真实订阅")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("无法读取 Chrome 测试订阅")
	}
	var fixture struct {
		PublicKey    string
		PrivateKey   string
		Subscription json.RawMessage
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal("Chrome 测试订阅格式非法")
	}
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	settings, _ := config.BindCalendarProperties(nil)
	events := calendar.NewEvents(f.Pool(), settings, time.UTC)
	push, err := calendar.NewPush(events, true, fixture.PublicKey, fixture.PrivateKey, "mailto:fixture@example.com")
	if err != nil {
		t.Fatal(err)
	}
	defer push.Close()
	handler := push.Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/reminders/push/subscriptions", bytes.NewReader(fixture.Subscription))
	req.Header.Set("Content-Type", "application/json")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, req)
	if response.Code != http.StatusOK {
		t.Fatalf("订阅 HTTP %d", response.Code)
	}
	now := time.Now().UTC().Truncate(time.Microsecond).Add(10 * time.Millisecond)
	title := "P5 browser delivery fixture"
	lead := 0
	event, err := events.Create(ctx, calendar.EventRequest{Title: &title, StartsAt: &now, EndsAt: &now, Lead: &lead})
	if err != nil {
		t.Fatal(err)
	}
	defer events.Delete(ctx, event.ID)
	if _, err := events.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	if err := push.Dispatch(ctx, now); err != nil {
		t.Fatal(err)
	}
	var state string
	err = f.Pool().WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT d.state FROM web_push_delivery d JOIN reminder r ON r.id=d.reminder_id WHERE r.source_id=$1`, event.ID).Scan(&state)
	})
	if err != nil || state != "SENT" {
		t.Fatalf("browser delivery state = %s, %v", state, err)
	}
}

func TestP5AuthenticatedAgentAndAutoMemory(t *testing.T) {
	var modelSawMemory atomic.Bool
	var modelSawSchema atomic.Bool
	var captures atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Stream   bool
			Messages []struct{ Role, Content string }
			Tools    []struct{ Function struct{ Name string } }
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		if !request.Stream {
			captures.Add(1)
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"choices":[{"message":{"role":"assistant","content":"[\"以后始终采用公制单位\"]"}}]}`)
			return
		}
		var prompt, user string
		var toolReply bool
		for _, message := range request.Messages {
			switch message.Role {
			case "system":
				prompt = message.Content
			case "user":
				user = message.Content
			case "tool":
				toolReply = true
			}
		}
		if strings.Contains(prompt, "每周五提交工作报告") {
			modelSawMemory.Store(true)
		}
		for _, tool := range request.Tools {
			if tool.Function.Name == "saveMemory" {
				modelSawSchema.Store(true)
			}
		}
		switch {
		case toolReply:
			p4Text(w, "已处理请求")
		case strings.HasPrefix(user, "请记住"):
			p4Tool(w, "saveMemory", `{"content":"每周五提交工作报告"}`)
		case strings.HasPrefix(user, "请创建日程"):
			p4Tool(w, "createCalendarEvent", `{"title":"P5 agent calendar","startsAt":"2026-11-04T01:00:00Z","endsAt":"2026-11-04T01:30:00Z"}`)
		case strings.HasPrefix(user, "请删除日程 "):
			p4Tool(w, "deleteCalendarEvent", fmt.Sprintf(`{"eventIds":[%q]}`, strings.TrimPrefix(user, "请删除日程 ")))
		default:
			p4Text(w, "已处理请求")
		}
	}))
	defer upstream.Close()
	f := chatFixtureFoundation(t, map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password",
		"app.security.csrf-enabled": "true", "app.calendar.scan-enabled": "false", "app.context.auto-memory.enabled": "true"}, upstream, true)
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	client := server.Client()
	client.Timeout = 10 * time.Second
	for _, path := range []string{"/memories", "/calendar/events", "/reminders", "/reminders/push"} {
		if status, _ := appRequest(t, client, "GET", server.URL+"/api/v1"+path, "", ""); status != 401 {
			t.Fatalf("%s anonymous status = %d", path, status)
		}
	}
	jar, _ := cookiejar.New(nil)
	client.Jar = jar
	csrf := func() string {
		_, envelope := appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
		return envelope["data"].(map[string]any)["token"].(string)
	}
	if status, _ := appRequest(t, client, "POST", server.URL+"/api/v1/auth/login", csrf(), `{"username":"admin","password":"fixture-password"}`); status != 200 {
		t.Fatal("login status", status)
	}
	token := csrf()
	request := func(method, path, body string, status int) any {
		t.Helper()
		got, payload := appRequest(t, client, method, server.URL+"/api/v1"+path, token, body)
		if got != status {
			t.Fatalf("%s %s = %d, want %d, payload=%v", method, path, got, status, payload)
		}
		return payload["data"]
	}
	session := request("POST", "/chat/sessions", `{"title":"P5 agent fixture"}`, 201).(map[string]any)
	conversation := session["conversationId"].(string)
	defer request("DELETE", "/chat/sessions/"+conversation, "", 200)
	send := func(content string) {
		t.Helper()
		req, err := http.NewRequest(http.MethodPost, server.URL+"/api/v1/chat/sessions/"+conversation+"/stream",
			strings.NewReader(fmt.Sprintf(`{"content":%q,"useKnowledge":false}`, content)))
		if err != nil {
			t.Fatal(err)
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("X-CSRF-TOKEN", token)
		response, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		body, err := io.ReadAll(response.Body)
		if err != nil || response.StatusCode != 200 || !strings.Contains(string(body), "event:done") && !strings.Contains(string(body), "event: done") {
			t.Fatalf("turn %q: %d %s %v", content, response.StatusCode, body, err)
		}
	}
	send("请记住我的周报习惯")
	send("请创建日程")
	list := request("GET", "/calendar/events?from=2026-11-04T00:00:00Z&to=2026-11-05T00:00:00Z", "", 200).(map[string]any)
	items := list["items"].([]any)
	if len(items) != 1 || !modelSawMemory.Load() || !modelSawSchema.Load() {
		t.Fatalf("event count=%d, memory=%v, schema=%v", len(items), modelSawMemory.Load(), modelSawSchema.Load())
	}
	event := items[0].(map[string]any)["eventId"].(string)
	send("请删除日程 " + event)
	request("GET", "/calendar/events/"+event, "", 200)
	pending := request("GET", "/agent/confirmations?conversationId="+conversation, "", 200).([]any)
	if len(pending) != 1 {
		t.Fatalf("confirmations = %+v", pending)
	}
	entry := pending[0].(map[string]any)
	request("POST", "/agent/confirmations/"+entry["id"].(string)+"/confirm",
		fmt.Sprintf(`{"token":%q}`, entry["token"]), 200)
	request("GET", "/calendar/events/"+event, "", 404)
	deadline := time.Now().Add(5 * time.Second)
	for {
		memories := request("GET", "/memories", "", 200).([]any)
		var manual, auto bool
		for _, raw := range memories {
			entry := raw.(map[string]any)
			manual = manual || entry["source"] == "MANUAL" && entry["content"] == "每周五提交工作报告"
			auto = auto || entry["source"] == "AUTO" && entry["content"] == "以后始终采用公制单位"
		}
		if manual && auto && captures.Load() > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("manual=%v, auto=%v, captures=%d", manual, auto, captures.Load())
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestP5MemoryConcurrentCreateAndBackfill(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	store, err := memory.NewStore(ctx, f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	var workers sync.WaitGroup
	ids := make(chan string, 12)
	for range 12 {
		workers.Go(func() {
			entry, err := store.Create(ctx, "P5 concurrent memory")
			if err != nil {
				t.Error(err)
				return
			}
			ids <- entry.ID
		})
	}
	workers.Wait()
	close(ids)
	var winner string
	var count int
	for id := range ids {
		count++
		if winner != "" && id != winner {
			t.Fatal("concurrent create wrote duplicate")
		}
		winner = id
	}
	if count != 12 {
		t.Fatalf("create successes = %d", count)
	}
	defer store.Delete(ctx, winner)
	second, err := store.Create(ctx, "P5 backfill beyond first batch")
	if err != nil {
		t.Fatal(err)
	}
	defer store.Delete(ctx, second.ID)
	store.UseEmbedding(memoryEmbedding{}, "fixture-backfill", 0.92)
	if err := store.Backfill(ctx, 1); err != nil {
		t.Fatal(err)
	}
	err = f.Pool().WithConn(ctx, func(conn *sql.Conn) error {
		var count int
		err := conn.QueryRowContext(ctx, `SELECT count(*) FROM memory WHERE id=ANY($1::uuid[]) AND embedding_model='fixture-backfill' AND embedded_at IS NOT NULL`, []string{winner, second.ID}).Scan(&count)
		if err != nil || count != 2 {
			t.Fatalf("backfilled = %d, %v", count, err)
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestP5TaskReminderLifecycle(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	settings, _ := config.BindCalendarProperties(nil)
	events := calendar.NewEvents(f.Pool(), settings, time.UTC)
	storage, err := task.NewPostgresStore(f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	tasks, err := task.NewService(storage)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().UTC().Truncate(time.Microsecond)
	due := now.Add(-time.Hour)
	entry, err := tasks.Create(ctx, task.CreateRequest{Title: "P5 initial task", DueAt: &due})
	if err != nil {
		t.Fatal(err)
	}
	defer tasks.Delete(ctx, entry.ID)
	expect := func(count int, title string) {
		t.Helper()
		items, err := events.Reminders(ctx, "PENDING", true, 10, now)
		if err != nil || len(items) != count || count == 1 && (items[0].Title != title || items[0].SourceType != "TASK" || items[0].SourceID != entry.ID) {
			t.Fatalf("due reminders = %+v, %v", items, err)
		}
	}
	if result, err := events.Scan(ctx, now); err != nil || result.Created != 1 {
		t.Fatalf("scan = %+v %v", result, err)
	}
	expect(1, entry.Title)
	appExec(t, f, `UPDATE task SET title='P5 renamed task' WHERE id='`+entry.ID+`'`)
	if _, err := events.Scan(ctx, now.Add(48*time.Hour)); err != nil {
		t.Fatal(err)
	}
	expect(1, "P5 renamed task")
	if _, err := tasks.SetCompleted(ctx, entry.ID, true); err != nil {
		t.Fatal(err)
	}
	if result, err := events.Scan(ctx, now); err != nil || result.Created != 0 {
		t.Fatalf("completed scan = %+v %v", result, err)
	}
	expect(0, "")
	if _, err := tasks.SetCompleted(ctx, entry.ID, false); err != nil {
		t.Fatal(err)
	}
	if _, err := events.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	expect(1, "P5 renamed task")
	if err := tasks.Delete(ctx, entry.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := events.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	expect(0, "")
}
