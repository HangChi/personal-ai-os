//go:build integration

package chat

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需要原42迁移创建的独立测试库")
	}
	o := database.DefaultPostgresOptions()
	o.Host, o.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	o.Username, o.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || o.Host != "127.0.0.1" || o.Database != "personalos_go_test" || o.Username != "go_fixture" {
		t.Fatal("只允许专用临时数据库")
	}
	o.Port, o.SSLMode, o.RootCert = port, os.Getenv("GO_TEST_POSTGRES_SSL_MODE"), os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(o)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	pool, err := database.Open(ctx, connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := pool.Close(); err != nil {
			t.Error(err)
		}
	})
	return pool
}
func fixtureService(t *testing.T, pool *database.Pool, clean AttachmentCleaner) (*Service, *PostgresStore) {
	t.Helper()
	store, err := NewPostgresStore(pool)
	if err != nil {
		t.Fatal(err)
	}
	svc, err := NewService(store, clean)
	if err != nil {
		t.Fatal(err)
	}
	return svc, store
}
func fixtureSession(t *testing.T, svc *Service, title string) SessionDTO {
	t.Helper()
	s, err := svc.Create(context.Background(), &title)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := svc.Delete(context.Background(), s.ConversationID); err != nil {
			var problem *api.BusinessError
			if !errors.As(err, &problem) || problem.Code() != api.ResourceNotFound {
				t.Error(err)
			}
		}
	})
	return s
}
func execute(t *testing.T, pool *database.Pool, query string, args ...any) {
	t.Helper()
	if err := pool.WithConn(context.Background(), func(c *sql.Conn) error { _, err := c.ExecContext(context.Background(), query, args...); return err }); err != nil {
		t.Fatal(err)
	}
}

func TestPostgresChatSessionSearchAndMessagePagination(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, _ := fixtureService(t, pool, nil)
	when := time.Date(2026, 11, 4, 0, 0, 0, 0, time.UTC)
	svc.now = func() time.Time { return when }
	first := fixtureSession(t, svc, "100%_\\ Vue")
	second := fixtureSession(t, svc, "100xx Vue")
	for i := 1; i <= 7; i++ {
		now := when.Add(-time.Duration(i) * time.Minute)
		svc.now = func() time.Time { return now }
		if err := svc.Append(ctx, first.ConversationID, []NewMessage{{Role: "USER", Content: fmt.Sprintf("m%d", i)}}); err != nil {
			t.Fatal(err)
		}
	}
	for _, c := range []struct {
		limit, before *int
		want          []int
	}{{nil, nil, []int{1, 2, 3, 4, 5, 6, 7}}, {ptrInt(3), nil, []int{5, 6, 7}}, {ptrInt(3), ptrInt(5), []int{2, 3, 4}}, {ptrInt(3), ptrInt(2), []int{1}}, {nil, ptrInt(0), []int{1, 2, 3, 4, 5, 6, 7}}} {
		messages, err := svc.Messages(ctx, first.ConversationID, c.limit, c.before)
		if err != nil {
			t.Fatal(err)
		}
		seq := []int{}
		for _, m := range messages {
			seq = append(seq, m.SequenceNo)
			if m.Citations == nil || m.Attachments == nil {
				t.Fatal("null arrays")
			}
		}
		if !reflect.DeepEqual(seq, c.want) {
			t.Fatal(seq, c.want)
		}
	}
	before, err := svc.Require(ctx, first.ConversationID)
	if err != nil {
		t.Fatal(err)
	}
	model := "custom-outside-catalog"
	if _, err = svc.SetModel(ctx, first.ConversationID, &model); err != nil {
		t.Fatal(err)
	}
	name := "100%_\\ Renamed"
	renamed, err := svc.Rename(ctx, first.ConversationID, &name)
	if err != nil || !renamed.UpdatedAt.Equal(before.UpdatedAt) || renamed.MessageCount != 7 {
		t.Fatal(renamed, err)
	}
	page, err := svc.Search(ctx, SessionQuery{Keyword: "100%_\\", Size: 1})
	if err != nil || page.Total != 1 || len(page.Items) != 1 || page.Items[0].ConversationID != first.ConversationID || *page.Items[0].ChatModel != model {
		t.Fatal(page, err)
	}
	overflow, err := svc.Search(ctx, SessionQuery{Keyword: "100%_\\", Page: 2147483647, Size: 100})
	if err != nil || overflow.Total != 1 || len(overflow.Items) != 0 || overflow.Page != 2147483647 {
		t.Fatal(overflow, err)
	}
	list, err := svc.List(ctx)
	if err != nil || len(list) != 2 || list[0].ConversationID != second.ConversationID {
		t.Fatal(list, err)
	}
	_, err = svc.Messages(ctx, "missing", ptrInt(0), nil)
	if err == nil || !strings.Contains(err.Error(), "会话不存在") {
		t.Fatal(err)
	}
}
func ptrInt(n int) *int { return &n }

func TestPostgresChatAppendAcrossPoolsAndRollback(t *testing.T) {
	ctx := context.Background()
	a, b := fixturePool(t), fixturePool(t)
	first, _ := fixtureService(t, a, nil)
	second, _ := fixtureService(t, b, nil)
	session := fixtureSession(t, first, "concurrent")
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			svc := first
			if i%2 == 1 {
				svc = second
			}
			if err := svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: fmt.Sprint(i)}, {Role: "ASSISTANT", Content: "reply"}}); err != nil {
				t.Error(err)
			}
		}(i)
	}
	close(start)
	wg.Wait()
	messages, err := first.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(messages) != 48 {
		t.Fatal(len(messages), err)
	}
	for i, m := range messages {
		if m.SequenceNo != i+1 {
			t.Fatal("sequence gap")
		}
		if i%2 == 0 && m.Role != "USER" || i%2 == 1 && m.Role != "ASSISTANT" {
			t.Fatal("batch interleaved")
		}
	}
	before, err := first.Require(ctx, session.ConversationID)
	if err != nil {
		t.Fatal(err)
	}
	// PostgreSQL 文本拒绝 NUL：第二条失败，第一条插入和活动时间必须一同回滚。
	err = first.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: "must rollback"}, {Role: "ASSISTANT", Content: "\x00"}})
	if err == nil {
		t.Fatal("invalid text accepted")
	}
	current, _ := first.Require(ctx, session.ConversationID)
	messages, err = first.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(messages) != 48 || !before.UpdatedAt.Equal(current.UpdatedAt) {
		t.Fatal("partial append committed", err)
	}
	if err = first.Append(ctx, "invalid id!", nil); err != nil {
		t.Fatal("empty append should not access session", err)
	}
	activity := before.UpdatedAt.Add(time.Minute)
	first.now = func() time.Time { return activity }
	if err = first.Append(ctx, session.ConversationID, []NewMessage{{Role: "TOOL", Content: "tool result"}, {Role: "SYSTEM", Content: "system"}, {Role: "unknown", Content: "fallback"}}); err != nil {
		t.Fatal(err)
	}
	current, err = first.Require(ctx, session.ConversationID)
	if err != nil || !current.UpdatedAt.Equal(activity) || current.MessageCount != 51 {
		t.Fatal("append did not touch session", current, err)
	}
	messages, err = first.Messages(ctx, session.ConversationID, ptrInt(3), nil)
	if err != nil || len(messages) != 3 || messages[0].Role != "TOOL" || messages[1].Role != "SYSTEM" || messages[2].Role != "ASSISTANT" || messages[0].SequenceNo != 49 {
		t.Fatal("role persistence or fallback changed", messages, err)
	}
}

func TestPostgresChatCitationsSnapshotsAndCascade(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, _ := fixtureService(t, pool, func(context.Context, []Attachment) {})
	session := fixtureSession(t, svc, "citations")
	if err := svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: "question", Attachments: []Attachment{{ID: ptr("fixture-att"), Filename: ptr("需求.txt"), ContentType: ptr("text/plain"), Size: 12}}}, {Role: "ASSISTANT", Content: "answer"}}); err != nil {
		t.Fatal(err)
	}
	hits := []Citation{
		{DocumentID: "deleted-document", DocumentTitle: ptr("原文标题"), Filename: ptr("source.md"), ChunkIndex: 2, Content: "historical evidence"},
		{DocumentID: webCitationDocumentID, DocumentTitle: ptr("网页标题"), Filename: ptr("example.org"), Content: "web excerpt\nwith a newline", URL: "https://example.org/report"},
	}
	bad := append(append([]Citation{}, hits...), Citation{DocumentID: strings.Repeat("x", 65), DocumentTitle: ptr("bad"), Filename: ptr("bad.md")})
	if err := svc.AttachCitations(ctx, session.ConversationID, bad); err == nil {
		t.Fatal("bad citation accepted")
	}
	rows, err := svc.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(rows[1].Citations) != 0 {
		t.Fatal("partial citations committed", err)
	}
	if err = svc.AttachCitations(ctx, session.ConversationID, hits); err != nil {
		t.Fatal(err)
	}
	rows, err = svc.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(rows[1].Citations) != 2 || rows[1].Citations[0].Index != 1 || rows[1].Citations[0].DocumentID != "deleted-document" || len(rows[0].Attachments) != 1 {
		t.Fatal(rows, err)
	}
	ids := []int64{rows[0].ID, rows[1].ID}
	// 用全新服务/连接读取，引用不依赖内存或原知识文档的存续。
	other, _ := fixtureService(t, fixturePool(t), func(context.Context, []Attachment) {})
	if rows, err = other.Messages(ctx, session.ConversationID, nil, nil); err != nil || len(rows[1].Citations) != 2 {
		t.Fatal(rows, err)
	}
	if citation := rows[1].Citations[1]; citation.Index != 2 || citation.URL != hits[1].URL || citation.Content != hits[1].Content {
		t.Fatal(citation)
	}
	if err = svc.Delete(ctx, session.ConversationID); err != nil {
		t.Fatal(err)
	}
	err = pool.WithConn(ctx, func(c *sql.Conn) error {
		var n int
		err := c.QueryRowContext(ctx, `SELECT count(*) FROM chat_message_citation WHERE message_id IN($1,$2)`, ids[0], ids[1]).Scan(&n)
		if err == nil && n != 0 {
			return errors.New("orphan citations")
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}

func TestPostgresChatCitationsAcrossHistoryBatches(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, _ := fixtureService(t, pool, nil)
	session := fixtureSession(t, svc, "long history")
	total := citationBatchSize*2 + 1
	execute(t, pool, `INSERT INTO chat_message(conversation_id,message_type,content,sequence_no,created_at)
		SELECT $1,'ASSISTANT','message-'||n,n,now() FROM generate_series(1,$2::integer) n`, session.ConversationID, total)
	boundaries := []int{1, citationBatchSize, citationBatchSize + 1, citationBatchSize * 2, total}
	for _, seq := range boundaries {
		execute(t, pool, `INSERT INTO chat_message_citation(message_id,citation_index,document_id,document_title,filename,chunk_index,content,created_at)
			SELECT id,n,'snapshot-doc','历史标题','source.md',n,'evidence-'||n,now()
			FROM chat_message CROSS JOIN generate_series(1,2) n WHERE conversation_id=$1 AND sequence_no=$2`, session.ConversationID, seq)
	}
	rows, err := svc.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(rows) != total {
		t.Fatal("full history lost", len(rows), err)
	}
	wantCitations := map[int]bool{}
	for _, seq := range boundaries {
		wantCitations[seq] = true
	}
	for i, row := range rows {
		if row.SequenceNo != i+1 {
			t.Fatal("message order changed", i, row.SequenceNo)
		}
		if wantCitations[row.SequenceNo] {
			if len(row.Citations) != 2 || row.Citations[0].Index != 1 || row.Citations[1].Index != 2 || row.Citations[1].Content != "evidence-2" {
				t.Fatal("batch boundary citation lost or reordered", row.SequenceNo, row.Citations)
			}
		} else if len(row.Citations) != 0 {
			t.Fatal("citation attached to wrong message", row.SequenceNo)
		}
	}
}

func TestPostgresChatRewindTransactionAndCleanup(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	var cleaned []string
	svc, store := fixtureService(t, pool, func(_ context.Context, files []Attachment) {
		// 清理回调能再次取得同池连接，并看到提交后状态。
		for _, f := range files {
			cleaned = append(cleaned, value(f.ID))
		}
		if err := pool.WithConn(context.Background(), func(c *sql.Conn) error {
			var n int
			return c.QueryRowContext(context.Background(), `SELECT 1`).Scan(&n)
		}); err != nil {
			t.Error(err)
		}
	})
	session := fixtureSession(t, svc, "rewind")
	for i := 1; i <= 4; i++ {
		if err := svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "USER", Content: fmt.Sprint(i), Attachments: []Attachment{{ID: ptr(fmt.Sprint(i)), Filename: ptr("file"), Size: 1}}}}); err != nil {
			t.Fatal(err)
		}
	}
	if err := store.Transact(ctx, false, func(tx Tx) error {
		r, err := tx.Session(ctx, session.ConversationID, true)
		if err != nil {
			return err
		}
		r.Summary = ptr("old summary")
		r.SummaryUptoSeq = 3
		return tx.SaveSession(ctx, r)
	}); err != nil {
		t.Fatal(err)
	}
	withoutCleanup, _ := fixtureService(t, pool, nil)
	if _, err := withoutCleanup.Rewind(ctx, session.ConversationID, 3); err == nil {
		t.Fatal("missing lifecycle must refuse mutation")
	}
	if r, _ := svc.Require(ctx, session.ConversationID); r.SummaryUptoSeq != 3 {
		t.Fatal("summary changed on refusal")
	}
	// 只在独立临时库注入删除失败，验证摘要和消息回滚、清理回调未触发。
	execute(t, pool, `CREATE FUNCTION p4_chat_delete_guard() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic failure'; END $$`)
	execute(t, pool, `CREATE TRIGGER p4_chat_delete_guard BEFORE DELETE ON chat_message FOR EACH ROW EXECUTE FUNCTION p4_chat_delete_guard()`)
	_, err := svc.Rewind(ctx, session.ConversationID, 3)
	execute(t, pool, `DROP TRIGGER p4_chat_delete_guard ON chat_message; DROP FUNCTION p4_chat_delete_guard()`)
	if err == nil || len(cleaned) != 0 {
		t.Fatal("failed transaction cleaned files")
	}
	if r, _ := svc.Require(ctx, session.ConversationID); r.SummaryUptoSeq != 3 {
		t.Fatal("summary rollback failed")
	}
	n, err := svc.Rewind(ctx, session.ConversationID, 3)
	if err != nil || n != 2 || !reflect.DeepEqual(cleaned, []string{"3", "4"}) {
		t.Fatal(n, cleaned, err)
	}
	r, err := svc.Require(ctx, session.ConversationID)
	if err != nil || r.Summary != nil || r.SummaryUptoSeq != 0 {
		t.Fatal(r, err)
	}
	rows, err := svc.Messages(ctx, session.ConversationID, nil, nil)
	if err != nil || len(rows) != 2 || value(rows[0].Attachments[0].ID) != "1" {
		t.Fatal(rows, err)
	}
	if err = svc.Append(ctx, session.ConversationID, []NewMessage{{Role: "ASSISTANT", Content: "regenerated"}}); err != nil {
		t.Fatal(err)
	}
	rows, _ = svc.Messages(ctx, session.ConversationID, nil, nil)
	if rows[2].SequenceNo != 3 {
		t.Fatal("rewind sequence not reused")
	}
}

func TestPostgresChatHTTPStorageRoutes(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	svc, _ := fixtureService(t, pool, nil)
	zone, _ := time.LoadLocation("Asia/Shanghai")
	handler, err := NewHTTP(svc, zone, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	request := func(method, path, body string) (int, map[string]any) {
		t.Helper()
		req, _ := http.NewRequest(method, server.URL+path, strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		response, err := server.Client().Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		var result map[string]any
		if err = json.NewDecoder(response.Body).Decode(&result); err != nil {
			t.Fatal(err)
		}
		return response.StatusCode, result
	}
	status, created := request("POST", "/api/v1/chat/sessions", `{"title":"http test"}`)
	if status != 201 {
		t.Fatal(status, created)
	}
	id := created["data"].(map[string]any)["conversationId"].(string)
	base := "/api/v1/chat/sessions/" + id
	t.Cleanup(func() { _ = svc.Delete(context.Background(), id) })
	if err = svc.Append(ctx, id, []NewMessage{{Role: "USER", Content: "hello"}, {Role: "ASSISTANT", Content: "world"}}); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ method, path, body string }{{"GET", "/api/v1/chat/sessions", ""}, {"GET", "/api/v1/chat/sessions/search?keyword=http", ""}, {"PATCH", base, `{"title":"renamed"}`}, {"PUT", base + "/model", `{"model":"custom"}`}, {"GET", base + "/messages?limit=1", ""}, {"POST", base + "/rewind?fromSequenceNo=2", ""}} {
		if code, result := request(c.method, c.path, c.body); code != 200 {
			t.Fatal(code, result)
		}
	}
	response, err := server.Client().Get(server.URL + base + "/export")
	if err != nil {
		t.Fatal(err)
	}
	content, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != 200 || !strings.Contains(string(content), "### 你\n\nhello") || response.Header.Get("Content-Type") != "text/markdown;charset=UTF-8" || !strings.HasPrefix(response.Header.Get("Content-Disposition"), "attachment; filename*=UTF-8''renamed-") {
		t.Fatal(response.StatusCode, string(content), response.Header)
	}
	if status, result := request("DELETE", base, ""); status != 200 || result["data"] != nil {
		t.Fatal(status, result)
	}
	if status, _ := request("GET", base+"/messages", ""); status != 404 {
		t.Fatal(status)
	}
}
