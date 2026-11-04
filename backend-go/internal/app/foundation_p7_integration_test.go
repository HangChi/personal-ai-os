//go:build integration

package app

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func p7Foundation(t *testing.T, extra map[string]string) *Foundation {
	t.Helper()
	var calls atomic.Int32
	up := p6Upstream(t, &calls)
	values := map[string]string{"app.runtime.role": "api", "app.rate-limit.enabled": "false", "app.auth.enabled": "false", "app.security.csrf-enabled": "false", "app.calendar.scan-enabled": "false", "app.memory.backfill.enabled": "false", "app.context.summary.enabled": "false", "app.context.auto-memory.enabled": "false", "app.chat.upload-dir": t.TempDir(), "spring.ai.model.chat": "openai", "spring.ai.model.embedding": "openai", "spring.ai.openai.api-key": "synthetic-p7", "spring.ai.openai.base-url": up.URL, "spring.ai.openai.chat.model": "fixture-p7", "spring.ai.openai.embedding.model": "fixture-1024", "app.knowledge.rag.enabled": "false", "spring.ai.mcp.server.enabled": "true"}
	for k, v := range extra {
		values[k] = v
	}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	f, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p7-foundation"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true, EnableP5: true, EnableP6: true, EnableP7: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		if e := f.Close(ctx); e != nil {
			t.Error(e)
		}
	})
	p7Clean(t, f)
	t.Cleanup(func() { p7Clean(t, f) })
	return f
}
func p7Clean(t *testing.T, f *Foundation) {
	t.Helper()
	appExec(t, f, `TRUNCATE knowledge_index_job,chat_session,chat_message,task,calendar_event,memory,knowledge_document,confirmation,operation_record,vector_store RESTART IDENTITY CASCADE;DELETE FROM knowledge_space WHERE id<>'00000000-0000-0000-0000-000000000001'`)
}
func p7Request(t *testing.T, f *Foundation, method, path string, body []byte) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(method, path, bytes.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, r)
	return w
}
func p7JSON(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var v map[string]any
	if e := json.Unmarshal(w.Body.Bytes(), &v); e != nil {
		t.Fatal(w.Code, w.Body.String(), e)
	}
	return v
}
func p7Count(t *testing.T, f *Foundation, table string) int {
	t.Helper()
	n := 0
	e := f.pool.WithConn(context.Background(), func(c *sql.Conn) error {
		return c.QueryRowContext(context.Background(), "SELECT count(*) FROM "+table).Scan(&n)
	})
	if e != nil {
		t.Fatal(e)
	}
	return n
}
func p7Seed(t *testing.T, f *Foundation) {
	t.Helper()
	appExec(t, f, `
 INSERT INTO task(id,title,status,priority,created_at,updated_at) SELECT md5('p7-task-'||i)::uuid,'P7token task '||i,'TODO','MEDIUM','2026-01-01Z','2026-01-01Z' FROM generate_series(1,405) i;
 INSERT INTO task_tag(task_id,tag_index,tag) SELECT id,0,'P7tag' FROM task;
 INSERT INTO chat_session(conversation_id,title,created_at,updated_at) VALUES('p7-a','P7token title','2026-01-01Z','2026-01-01Z'),('p7-b','history','2026-01-01Z','2026-01-01Z');
 INSERT INTO chat_message(conversation_id,message_type,content,sequence_no,created_at) SELECT 'p7-a','USER','P7token message '||i,i,'2026-01-01Z' FROM generate_series(1,405) i;
 INSERT INTO chat_message(conversation_id,message_type,content,sequence_no,created_at) VALUES('p7-b','ASSISTANT','P7token cited',1,'2026-01-01Z');
 INSERT INTO chat_message_citation(message_id,citation_index,document_id,chunk_index,document_title,filename,content,created_at) SELECT id,1,'00000000-0000-0000-0000-000000000701',0,'P7token doc','p7.md','P7token chunk','2026-01-01Z' FROM chat_message WHERE conversation_id='p7-b';
 INSERT INTO calendar_event(id,title,all_day,starts_at,ends_at,recurrence_type,reminder_lead_minutes,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000700','P7token event',false,'2026-11-04T09:00Z','2026-11-04T10:00Z','NONE',15,'2026-01-01Z','2026-01-01Z');
 INSERT INTO memory(id,content,content_hash,source,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000701','P7token old',repeat('a',64),'MANUAL','2026-01-01Z','2026-01-01Z'),('00000000-0000-0000-0000-000000000702','P7token current',repeat('b',64),'MANUAL','2026-01-01Z','2026-01-01Z');
 UPDATE memory SET superseded_by='00000000-0000-0000-0000-000000000702' WHERE id='00000000-0000-0000-0000-000000000701';
 UPDATE memory SET pinned=true,hit_count=17,last_used_at='2026-10-03T10:00Z',updated_at='2026-10-03T10:00Z' WHERE id='00000000-0000-0000-0000-000000000702';
 UPDATE task SET status='DONE',previous_status='DOING',priority='HIGH',completed_at='2026-10-01T10:00Z',due_at='2026-10-01T09:00Z',description='P7token restored state' WHERE title='P7token task 1';
 INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count) VALUES('00000000-0000-0000-0000-000000000701','P7token doc','p7.md','text/markdown',10,100,205),('00000000-0000-0000-0000-000000000702','opaque','body.md','text/markdown',10,100,1);
 INSERT INTO vector_store(id,content,metadata,embedding) SELECT gen_random_uuid(),'P7token chunk '||i,json_build_object('document_id','00000000-0000-0000-0000-000000000701','document_title','P7token doc','filename','p7.md','chunk_index',i-1),('[1,'||repeat('0,',1022)||'0]')::vector FROM generate_series(1,205) i;
 INSERT INTO vector_store(id,content,metadata,embedding) VALUES(gen_random_uuid(),'P7token body',json_build_object('document_id','00000000-0000-0000-0000-000000000702','document_title','opaque','filename','body.md','chunk_index',0),('[1,'||repeat('0,',1022)||'0]')::vector);
 INSERT INTO knowledge_space(id,name) VALUES('00000000-0000-0000-0000-000000000703','P7space');
 UPDATE knowledge_source SET space_id='00000000-0000-0000-0000-000000000703',version='v7',scope='local',authority='fixture',effective_from='2026-01-01' WHERE document_id='00000000-0000-0000-0000-000000000701';
 `)
}

func TestP7MCPAndFiveModuleSearchPostgres(t *testing.T) {
	f := p7Foundation(t, nil)
	p7Seed(t, f)
	w := p7Request(t, f, "GET", "/api/v1/mcp/servers", nil)
	if w.Code != 200 || p7JSON(t, w)["data"].(map[string]any)["enabled"] != false {
		t.Fatal(w.Code, w.Body.String())
	}
	w = p7Request(t, f, "GET", "/api/v1/search?keyword=P7token&limit=5", nil)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	data := p7JSON(t, w)["data"].(map[string]any)
	groups := data["groups"].([]any)
	if len(groups) != 5 {
		t.Fatal(data)
	}
	expected := []string{"任务", "对话", "日程", "知识库", "记忆"}
	for i, g := range groups {
		if g.(map[string]any)["module"] != expected[i] {
			t.Fatal(groups)
		}
	}
	for _, index := range []int{1, 3} {
		hits := groups[index].(map[string]any)["hits"].([]any)
		if len(hits) != 2 || hits[0].(map[string]any)["matchedField"] != "标题" {
			t.Fatal(hits)
		}
	}
	appExec(t, f, `INSERT INTO task(title,status,priority,created_at,updated_at) VALUES('100%_\','TODO','MEDIUM',now(),now()),('100anything','TODO','MEDIUM',now(),now())`)
	w = p7Request(t, f, "GET", "/api/v1/search?keyword=100%25_%5C", nil)
	if w.Code != 200 || p7JSON(t, w)["data"].(map[string]any)["totalHits"] != float64(1) {
		t.Fatal(w.Code, w.Body.String())
	}
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	session, e := sdk.NewClient(&sdk.Implementation{Name: "p7-test", Version: "1"}, nil).Connect(context.Background(), &sdk.StreamableClientTransport{Endpoint: server.URL + "/mcp", DisableStandaloneSSE: true}, nil)
	if e != nil {
		t.Fatal(e)
	}
	defer session.Close()
	for _, name := range []string{"personal_list_tasks", "personal_list_calendar", "personal_list_memories", "personal_search_knowledge"} {
		args := map[string]any{}
		if name == "personal_search_knowledge" {
			args["query"] = "P7token"
		}
		if name == "personal_list_calendar" {
			args["from"] = "2026-11-04T00:00:00Z"
		}
		result, e := session.CallTool(context.Background(), &sdk.CallToolParams{Name: name, Arguments: args})
		if e != nil || result.IsError || len(result.Content) == 0 {
			t.Fatal(name, result, e)
		}
	}
	if p7Count(t, f, "confirmation") != 0 || p7Count(t, f, "operation_record") != 0 {
		t.Fatal("personal MCP exposed writes")
	}
	// A failed module is rolled back independently and the remaining four still return.
	appExec(t, f, `ALTER TABLE memory RENAME TO p7_hidden_memory`)
	defer appExec(t, f, `ALTER TABLE p7_hidden_memory RENAME TO memory`)
	w = p7Request(t, f, "GET", "/api/v1/search?keyword=P7token", nil)
	if w.Code != 200 || len(p7JSON(t, w)["data"].(map[string]any)["groups"].([]any)) != 4 {
		t.Fatal(w.Code, w.Body.String())
	}
}

func TestP7StreamingExportImportRoundTripPostgres(t *testing.T) {
	f := p7Foundation(t, nil)
	p7Seed(t, f)
	w := p7Request(t, f, "GET", "/api/v1/export", nil)
	if w.Code != 200 || !strings.Contains(w.Header().Get("Content-Disposition"), "attachment;") {
		t.Fatal(w.Code, w.Body.String())
	}
	raw := append([]byte{}, w.Body.Bytes()...)
	if bytes.Contains(raw, []byte(`"embedding":`)) {
		t.Fatal("export contains vector embeddings")
	}
	original := p7JSON(t, w)
	if original["formatVersion"] != "1" || original["code"] != nil {
		t.Fatal("export envelope", original)
	}
	counts := original["counts"].(map[string]any)
	if counts["tasks"] != float64(405) || counts["sessions"] != float64(2) || counts["knowledgeDocuments"] != float64(2) {
		t.Fatal(counts)
	}
	session := original["sessions"].([]any)[0].(map[string]any)
	if len(session["messages"].([]any)) != 405 {
		t.Fatal("nested messages truncated")
	}
	doc := original["knowledgeDocuments"].([]any)[0].(map[string]any)
	if len(doc["chunks"].([]any)) != 205 {
		t.Fatal("nested chunks truncated")
	}
	w = p7Request(t, f, "POST", "/api/v1/import", raw)
	if w.Code != 409 {
		t.Fatal("occupied database accepted", w.Code, w.Body.String())
	}
	p7Clean(t, f)
	w = p7Request(t, f, "POST", "/api/v1/import", raw)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	result := p7JSON(t, w)["data"].(map[string]any)
	if result["messages"] != float64(406) || result["vectors"] != float64(206) || result["tasks"] != float64(405) || len(result["notes"].([]any)) != 1 {
		t.Fatal(result)
	}
	restored := p7JSON(t, p7Request(t, f, "GET", "/api/v1/export", nil))
	for _, key := range []string{"tasks", "calendarEvents", "memories"} {
		a, _ := json.Marshal(original[key])
		b, _ := json.Marshal(restored[key])
		if !bytes.Equal(a, b) {
			t.Fatal("round trip mismatch", key)
		}
	}
	if p7Count(t, f, "chat_message_citation") != 0 {
		t.Fatal("derived citations unexpectedly restored")
	}
	var name, version, scope, effectiveFrom string
	var pointers int
	e := f.pool.WithConn(context.Background(), func(c *sql.Conn) error {
		if e := c.QueryRowContext(context.Background(), `SELECT k.name,s.version,s.scope,s.effective_from::text FROM knowledge_source s JOIN knowledge_space k ON s.space_id=k.id WHERE document_id='00000000-0000-0000-0000-000000000701'`).Scan(&name, &version, &scope, &effectiveFrom); e != nil {
			return e
		}
		return c.QueryRowContext(context.Background(), `SELECT count(*) FROM memory WHERE superseded_by='00000000-0000-0000-0000-000000000702'`).Scan(&pointers)
	})
	if e != nil || name != "P7space" || version != "v7" || scope != "local" || effectiveFrom != "2026-01-01" || pointers != 1 {
		t.Fatal(name, version, scope, effectiveFrom, pointers, e)
	}
	w = p7Request(t, f, "GET", "/api/v1/search?keyword=P7token", nil)
	if w.Code != 200 || len(p7JSON(t, w)["data"].(map[string]any)["groups"].([]any)) != 5 {
		t.Fatal(w.Code, w.Body.String())
	}
}

type p7Embed func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error)

func (f p7Embed) Embed(ctx context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	return f(ctx, r)
}
func TestP7ImportAtomicityConcurrencyAndEmbeddingBoundary(t *testing.T) {
	f := p7Foundation(t, nil)
	service := &dataimport.Service{Pool: f.pool, Knowledge: f.p6.Store}
	payload := []byte(`{"formatVersion":"1","tasks":[{"id":"00000000-0000-0000-0000-000000000711","title":"restored"}],"knowledgeDocuments":[{"document":{"id":"00000000-0000-0000-0000-000000000712","title":"restored","filename":"import.md"},"chunks":["P7token evidence"]}]}`)
	old := f.p6.Store.Embedding
	f.p6.Store.Embedding = p7Embed(func(ctx context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		if e := f.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
			_, e := tx.ExecContext(ctx, `LOCK TABLE task IN ACCESS EXCLUSIVE MODE NOWAIT`)
			return e
		}); e != nil {
			t.Fatal("embedding holds business locks", e)
		}
		return model.EmbeddingResponse{}, errors.New("fixture embedding failure")
	})
	if _, e := service.Import(context.Background(), payload); e == nil || p7Count(t, f, "task") != 0 || p7Count(t, f, "vector_store") != 0 {
		t.Fatal("embedding failure committed", e)
	}
	f.p6.Store.Embedding = old
	bad := bytes.Replace(payload, []byte(`"chunks":[`), []byte(`"source":{"spaceName":"P7space","lifecycle":"INVALID"},"chunks":[`), 1)
	if _, e := service.Import(context.Background(), bad); e == nil || p7Count(t, f, "task") != 0 || p7Count(t, f, "vector_store") != 0 || p7Count(t, f, "knowledge_document") != 0 {
		t.Fatal("transaction rollback lost", e)
	}
	// A writer racing the external embedding is detected by the second empty check.
	f.p6.Store.Embedding = p7Embed(func(ctx context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		appExec(t, f, `INSERT INTO task(title,status,priority,created_at,updated_at) VALUES('concurrent','TODO','MEDIUM',now(),now())`)
		return old.Embed(ctx, r)
	})
	if _, e := service.Import(context.Background(), payload); e == nil || p7Count(t, f, "task") != 1 || p7Count(t, f, "vector_store") != 0 {
		t.Fatal("concurrent write mixed into import", e)
	}
	p7Clean(t, f)
	f.p6.Store.Embedding = old
	var successes atomic.Int32
	var wg sync.WaitGroup
	for range 2 {
		wg.Go(func() {
			if _, e := service.Import(context.Background(), payload); e == nil {
				successes.Add(1)
			}
		})
	}
	wg.Wait()
	if successes.Load() != 1 || p7Count(t, f, "task") != 1 || p7Count(t, f, "vector_store") != 1 {
		t.Fatal("concurrent imports committed twice", successes.Load())
	}
}

type p7SnapshotWriter struct {
	bytes.Buffer
	mutate  func()
	flushed bool
}

func (w *p7SnapshotWriter) Flush() {
	if !w.flushed {
		w.flushed = true
		w.mutate()
	}
}

func TestP7ExportSnapshotAcrossBatchesAndImportConstraintRollback(t *testing.T) {
	f := p7Foundation(t, nil)
	p7Seed(t, f)
	writer := &p7SnapshotWriter{mutate: func() {
		appExec(t, f, `UPDATE task SET title='changed-during-export'; INSERT INTO task(title,status,priority,created_at,updated_at) VALUES('new-during-export','TODO','MEDIUM',now(),now())`)
	}}
	exporter := &dataexport.Service{Pool: f.pool, Name: "fixture", Version: "1", Contributors: []dataexport.Contributor{task.ExportContributor{}}}
	if e := exporter.Write(context.Background(), writer); e != nil {
		t.Fatal(e)
	}
	var result struct {
		Tasks []task.DTO `json:"tasks"`
	}
	if e := json.Unmarshal(writer.Bytes(), &result); e != nil || len(result.Tasks) != 405 || !writer.flushed {
		t.Fatal("incomplete snapshot", len(result.Tasks), e)
	}
	for _, item := range result.Tasks {
		if !strings.HasPrefix(item.Title, "P7token task ") {
			t.Fatal("concurrent writes entered export snapshot", item.Title)
		}
	}
	p7Clean(t, f)
	raw := []byte(`{"formatVersion":"1","tasks":[{"id":"00000000-0000-0000-0000-000000000711","title":"first"},{"id":"00000000-0000-0000-0000-000000000711","title":"duplicate"}]}`)
	w := p7Request(t, f, "POST", "/api/v1/import", raw)
	if w.Code != 409 || p7Count(t, f, "task") != 0 {
		t.Fatal("constraint failure did not roll back or map to 409", w.Code, w.Body.String())
	}
}

func TestP7EmptyExportPendingJobsAndSanitizedImport(t *testing.T) {
	f := p7Foundation(t, nil)
	w := p7Request(t, f, "GET", "/api/v1/export", nil)
	empty := p7JSON(t, w)
	for _, key := range []string{"tasks", "sessions", "calendarEvents", "memories", "knowledgeDocuments"} {
		if len(empty[key].([]any)) != 0 || empty["counts"].(map[string]any)[key] != float64(0) {
			t.Fatal("empty export contract", empty)
		}
	}
	appExec(t, f, `INSERT INTO knowledge_index_job(id,document_id,replacing,filename,digest) VALUES(gen_random_uuid(),gen_random_uuid(),false,'pending.md',repeat('a',64))`)
	w = p7Request(t, f, "POST", "/api/v1/import", []byte(`{"formatVersion":"1"}`))
	if w.Code != 409 || !strings.Contains(w.Body.String(), "knowledge_index_job=1") {
		t.Fatal("pending index input ignored", w.Code, w.Body.String())
	}
	p7Clean(t, f)
	raw := []byte(`{"formatVersion":"1","sessions":[{"session":{"conversationId":"p7-dirty","title":"","createdAt":"2026-01-01T00:00:00Z"},"messages":[{"role":"SYSTEM","content":"skip"},{"role":"USER","content":"keep","sequenceNo":2},{"role":" user ","content":"skip"}]}],"tasks":[{"id":"00000000-0000-0000-0000-000000000721","title":"fallback","status":"WHATEVER","priority":null},{"id":"00000000-0000-0000-0000-000000000722","title":" "}],"calendarEvents":[{"id":"00000000-0000-0000-0000-000000000723","title":"event","startsAt":"2026-01-01T00:00:00Z","recurrenceType":"INVALID"}]}`)
	w = p7Request(t, f, "POST", "/api/v1/import", raw)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	data := p7JSON(t, w)["data"].(map[string]any)
	if data["messages"] != float64(1) || data["tasks"] != float64(1) || data["calendarEvents"] != float64(1) {
		t.Fatal(data)
	}
	exported := p7JSON(t, p7Request(t, f, "GET", "/api/v1/export", nil))
	task := exported["tasks"].([]any)[0].(map[string]any)
	session := exported["sessions"].([]any)[0].(map[string]any)["session"].(map[string]any)
	event := exported["calendarEvents"].([]any)[0].(map[string]any)
	if task["status"] != "TODO" || task["priority"] != "MEDIUM" || session["title"] != "导入的会话" || event["recurrenceType"] != "NONE" || event["reminderLeadMinutes"] != float64(15) {
		t.Fatal(task, session, event)
	}
}

func TestP7MCPChatHTTPAndPersistentConfirmation(t *testing.T) {
	var reads, writes, returned atomic.Int32
	remote := sdk.NewServer(&sdk.Implementation{Name: "p7-remote", Version: "1"}, nil)
	for _, name := range []string{"read", "write"} {
		remote.AddTool(&sdk.Tool{Name: name, InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}}, func(context.Context, *sdk.CallToolRequest) (*sdk.CallToolResult, error) {
			if name == "read" {
				reads.Add(1)
			} else {
				writes.Add(1)
			}
			return &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: "p7-" + name + "-result"}}}, nil
		})
	}
	protocol := httptest.NewServer(sdk.NewStreamableHTTPHandler(func(*http.Request) *sdk.Server { return remote }, &sdk.StreamableHTTPOptions{Stateless: true, JSONResponse: true}))
	defer protocol.Close()
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var request struct {
			Messages []struct {
				Role    string `json:"role"`
				Content string `json:"content"`
			} `json:"messages"`
			Tools json.RawMessage `json:"tools"`
		}
		if e := json.NewDecoder(r.Body).Decode(&request); e != nil || len(request.Messages) == 0 {
			t.Error("invalid model request", e)
			http.Error(w, "fixture request", 400)
			return
		}
		last := request.Messages[len(request.Messages)-1]
		if last.Role == "tool" {
			if !strings.Contains(last.Content, "p7-read-result") && !strings.Contains(last.Content, "REQUIRES_CONFIRMATION") {
				t.Error("MCP result did not return to model context", last.Content)
			}
			returned.Add(1)
			p4Text(w, "P7 MCP completed")
			return
		}
		if !bytes.Contains(request.Tools, []byte("mcp_remote_read")) || !bytes.Contains(request.Tools, []byte("mcp_remote_write")) {
			t.Error("MCP tools absent from actual model request")
		}
		name := "read"
		if strings.Contains(last.Content, "write") {
			name = "write"
		}
		p4Tool(w, "mcp_remote_"+name, `{}`)
	}))
	defer upstream.Close()
	f := p7Foundation(t, map[string]string{"mcp.enabled": "true", "mcp.http-servers.remote.endpoint": protocol.URL, "mcp.http-servers.remote.auto-tools": "read", "mcp.http-servers.remote.connect-timeout": "5s", "spring.ai.openai.base-url": upstream.URL})
	var writeConversation string
	for _, name := range []string{"read", "write"} {
		w := p7Request(t, f, "POST", "/api/v1/chat/sessions", []byte(`{"title":"P7 MCP"}`))
		if w.Code != 201 {
			t.Fatal(w.Code, w.Body.String())
		}
		id := p7JSON(t, w)["data"].(map[string]any)["conversationId"].(string)
		if name == "write" {
			writeConversation = id
		}
		body, _ := json.Marshal(map[string]any{"content": name, "useKnowledge": false})
		w = p7Request(t, f, "POST", "/api/v1/chat/sessions/"+id+"/stream", body)
		if w.Code != 200 || !strings.Contains(w.Body.String(), "event: done") || !strings.Contains(w.Body.String(), "P7 MCP completed") {
			t.Fatal("actual MCP chat stream", w.Code, w.Body.String())
		}
	}
	if reads.Load() != 1 || writes.Load() != 0 || returned.Load() != 2 || p7Count(t, f, "confirmation") != 1 {
		t.Fatal("chat bypassed confirmation", reads.Load(), writes.Load(), returned.Load())
	}
	w := p7Request(t, f, "GET", "/api/v1/agent/confirmations?status=PENDING&conversationId="+writeConversation, nil)
	items := p7JSON(t, w)["data"].([]any)
	if len(items) != 1 {
		t.Fatal(items)
	}
	item := items[0].(map[string]any)
	if item["action"] != "MCP_TOOL_CALL" || !strings.Contains(item["summary"].(string), "server=remote") {
		t.Fatal(item)
	}
	// The callback created before reconnect must resolve the replacement session.
	w = p7Request(t, f, "POST", "/api/v1/mcp/servers/remote/reconnect", nil)
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var success atomic.Int32
	var wg sync.WaitGroup
	for range 12 {
		wg.Go(func() {
			body, _ := json.Marshal(map[string]any{"token": item["token"]})
			response := p7Request(t, f, "POST", "/api/v1/agent/confirmations/"+item["id"].(string)+"/confirm", body)
			if response.Code == 200 && p7JSON(t, response)["data"].(map[string]any)["executed"] == true {
				success.Add(1)
			}
		})
	}
	wg.Wait()
	if success.Load() != 1 || writes.Load() != 1 || p7Count(t, f, "operation_record") != 2 {
		t.Fatal("persistent confirmation/audit repeated or missing", success.Load(), writes.Load(), p7Count(t, f, "operation_record"))
	}
}
