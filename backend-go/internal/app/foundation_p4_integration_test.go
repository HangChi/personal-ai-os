//go:build integration

package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

func p4Foundation(t *testing.T, values map[string]string, upstream *httptest.Server) *Foundation {
	t.Helper()
	defaults := map[string]string{"app.rate-limit.enabled": "false", "app.context.summary.enabled": "false", "app.chat.upload-dir": t.TempDir(), "spring.ai.model.chat": "openai", "spring.ai.model.embedding": "none", "spring.ai.openai.api-key": "synthetic-p4-foundation", "spring.ai.openai.chat.model": "fixture-p4"}
	if upstream != nil {
		defaults["spring.ai.openai.base-url"] = upstream.URL
	}
	for k, v := range values {
		defaults[k] = v
	}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: defaults})
	if e != nil {
		t.Fatal(e)
	}
	var transport http.RoundTripper
	if upstream != nil {
		transport = upstream.Client().Transport
	}
	f, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p4-foundation"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true, ModelTransport: transport}, slog.New(slog.NewTextHandler(io.Discard, nil)))
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
	return f
}
func p4Text(w http.ResponseWriter, body string) {
	w.Header().Set("Content-Type", "text/event-stream")
	raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]string{"content": body}}}, "usage": map[string]int{"prompt_tokens": 12, "completion_tokens": 3, "total_tokens": 15}})
	fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
}
func p4Tool(w http.ResponseWriter, name, args string) {
	w.Header().Set("Content-Type", "text/event-stream")
	raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"delta": map[string]any{"tool_calls": []any{map[string]any{"index": 0, "id": "p4-call", "type": "function", "function": map[string]string{"name": name, "arguments": args}}}}}}})
	fmt.Fprintf(w, "data: %s\n\ndata: [DONE]\n\n", raw)
}
func TestFoundationP4AttachmentJanitorSwitch(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		t.Run(strconv.FormatBool(enabled), func(t *testing.T) {
			dir := t.TempDir()
			id := "12345678-1234-1234-1234-123456789012"
			path := filepath.Join(dir, id)
			if err := os.WriteFile(path, []byte("orphan"), 0600); err != nil {
				t.Fatal(err)
			}
			old := time.Now().Add(-2 * time.Hour)
			if err := os.Chtimes(path, old, old); err != nil {
				t.Fatal(err)
			}
			p4Foundation(t, map[string]string{"app.chat.upload-dir": dir, "app.chat.attachment-janitor-enabled": strconv.FormatBool(enabled), "app.chat.attachment-janitor-cron": "* * * * * *", "spring.ai.model.chat": "none"}, nil)
			deadline := time.Now().Add(2500 * time.Millisecond)
			for time.Now().Before(deadline) {
				_, err := os.Stat(path)
				if enabled && os.IsNotExist(err) {
					return
				}
				if !enabled && err != nil {
					t.Fatal("disabled janitor removed file", err)
				}
				time.Sleep(10 * time.Millisecond)
			}
			if enabled {
				t.Fatal("enabled cron never collected orphan")
			}
		})
	}
}

func TestFoundationP4FullSecurityMetricsVoiceAndWorkers(t *testing.T) {
	var calls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/audio/transcriptions" {
			_, _ = io.Copy(io.Discard, r.Body)
			fmt.Fprint(w, `{"text":"语音输入"}`)
			return
		}
		calls.Add(1)
		p4Text(w, "Go P4 回答")
	}))
	defer upstream.Close()
	f := p4Foundation(t, map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true", "management.endpoints.web.exposure.include": "health,info,metrics,prometheus"}, upstream)
	appExec(t, f, `DELETE FROM ai_model_profile; DELETE FROM ai_model_config`)
	defer appExec(t, f, `DELETE FROM ai_model_profile; DELETE FROM ai_model_config`)
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	client := server.Client()
	for _, path := range []string{"/chat/sessions", "/chat/context", "/usage", "/agent/tool-invocations"} {
		code, _ := appRequest(t, client, "GET", server.URL+"/api/v1"+path, "", "")
		if code != 401 {
			t.Fatal(path, code)
		}
	}
	if code, _ := appRequest(t, client, "POST", server.URL+"/api/v1/chat/sessions", "", `{"title":"blocked"}`); code != 403 {
		t.Fatal("CSRF", code)
	}
	jar, _ := cookiejar.New(nil)
	client.Jar = jar
	_, b := appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	token := b["data"].(map[string]any)["token"].(string)
	code, b := appRequest(t, client, "POST", server.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`)
	if code != 200 {
		t.Fatal(code, b)
	}
	_, b = appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	token = b["data"].(map[string]any)["token"].(string)
	code, b = appRequest(t, client, "POST", server.URL+"/api/v1/chat/sessions", token, `{"title":"P4 full chain"}`)
	if code != 201 {
		t.Fatal(code, b)
	}
	id := b["data"].(map[string]any)["conversationId"].(string)
	defer f.Chat().Service.Delete(context.Background(), id)
	// 音频档案由真实 P3 HTTP 写入，P4 只消费转写正文。
	profile := fmt.Sprintf(`{"name":"voice","baseUrl":%q,"apiKey":"synthetic-p4-foundation","chatModels":["fixture-p4"],"audioModels":["whisper-fixture"]}`, upstream.URL)
	code, b = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/profiles", token, profile)
	if code != 200 {
		t.Fatal(code, b)
	}
	pid := b["data"].(map[string]any)["id"].(string)
	if code, b = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/profiles/"+pid+"/activate", token, ""); code != 200 {
		t.Fatal(code, b)
	}
	var data bytes.Buffer
	writer := multipart.NewWriter(&data)
	file, _ := writer.CreateFormFile("file", "voice.webm")
	_, _ = file.Write([]byte("synthetic audio"))
	_ = writer.Close()
	req, _ := http.NewRequest("POST", server.URL+"/api/v1/chat/transcribe", &data)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	req.Header.Set("X-CSRF-TOKEN", token)
	response, e := client.Do(req)
	if e != nil {
		t.Fatal(e)
	}
	var trans map[string]any
	_ = json.NewDecoder(response.Body).Decode(&trans)
	response.Body.Close()
	if response.StatusCode != 200 || trans["data"].(map[string]any)["text"] != "语音输入" {
		t.Fatal(response.StatusCode, trans)
	}
	code, b = appRequest(t, client, "POST", server.URL+"/api/v1/chat/sessions/"+id+"/runs", token, `{"content":"语音输入","useKnowledge":false}`)
	if code != 200 {
		t.Fatal(code, b)
	}
	rid := b["data"].(map[string]any)["id"].(string)
	deadline := time.Now().Add(5 * time.Second)
	for {
		run, e := f.Chat().Runs.Get(context.Background(), id, rid, 0)
		if e == nil && run.Status == "COMPLETED" {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("run timeout", run, e)
		}
		time.Sleep(10 * time.Millisecond)
	}
	for _, path := range []string{"/api/v1/chat/sessions/" + id + "/context", "/api/v1/conversations/" + id + "/plan", "/api/v1/usage", "/api/v1/agent/tool-invocations", "/actuator/metrics/personalos.chat.duration", "/actuator/metrics/personalos.chat.tokens"} {
		code, b = appRequest(t, client, "GET", server.URL+path, "", "")
		if code != 200 {
			t.Fatal(path, code, b)
		}
	}
	response, e = client.Get(server.URL + "/actuator/prometheus")
	if e != nil {
		t.Fatal(e)
	}
	raw, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if !strings.Contains(string(raw), "personalos_chat_duration_seconds_count{") || !strings.Contains(string(raw), "personalos_chat_tokens_total{") {
		t.Fatal("missing live metrics")
	}
	response, _ = client.Get(server.URL + "/readyz")
	response.Body.Close()
	if response.StatusCode != 503 || calls.Load() != 1 {
		t.Fatal("readiness/calls", response.StatusCode, calls.Load())
	}
	for _, role := range []string{"index", "review", "delivery"} {
		worker := p4Foundation(t, map[string]string{"app.runtime.role": role, "spring.ai.model.chat": "none"}, nil)
		if worker.Chat().Runs.Enabled {
			t.Fatal("worker started chat scheduler")
		}
		w := httptest.NewRecorder()
		worker.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/chat/sessions", nil))
		if w.Code != 404 || w.Body.Len() != 0 {
			t.Fatal(role, w.Code)
		}
	}
}

func TestFoundationP4LeastPrivilegesAndEveryMissingGrant(t *testing.T) {
	admin := fixtureFoundation(t, nil, nil)
	var secret [24]byte
	_, _ = rand.Read(secret[:])
	password := hex.EncodeToString(secret[:])
	role := "go_p4_" + password[:12]
	appExec(t, admin, "CREATE ROLE "+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'")
	t.Cleanup(func() { appExec(t, admin, "DROP OWNED BY "+role+";DROP ROLE "+role) })
	appExec(t, admin, `GRANT CONNECT ON DATABASE personalos_go_test TO `+role+`;GRANT USAGE ON SCHEMA public TO `+role+`;
 GRANT SELECT ON flyway_schema_history TO `+role+`;GRANT SELECT,INSERT,UPDATE ON runtime_rate_bucket,runtime_lease TO `+role+`;
 GRANT SELECT,INSERT,UPDATE,DELETE ON task,task_tag,operation_record,confirmation,reminder,ai_model_config,ai_model_profile,ai_model_profile_model,chat_session,chat_message,chat_message_citation,chat_context_preference,agent_plan,agent_plan_step,chat_run,chat_run_event,agent_question,agent_followup TO `+role+`;
 GRANT SELECT,INSERT ON token_usage TO `+role+`;GRANT USAGE ON SEQUENCE operation_record_id_seq,ai_model_profile_model_id_seq,chat_message_id_seq,chat_message_citation_id_seq,chat_run_event_id_seq,agent_followup_seq_seq,token_usage_id_seq TO `+role)
	opts := appPostgresOptions(t)
	opts.Username = role
	opts.Password = password
	opts.ApplicationName = "go-p4-acl"
	connector, e := database.NewPostgresConnector(opts)
	if e != nil {
		t.Fatal(e)
	}
	p, _ := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.model.chat": "none", "spring.ai.model.embedding": "none", "app.chat.upload-dir": t.TempDir()}})
	open := func() (*Foundation, error) {
		return OpenFoundation(context.Background(), p, FoundationOptions{Connector: connector, Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, EnableP4: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	}
	f, e := open()
	if e != nil {
		t.Fatal("minimal P4 role", e)
	}
	session, e := f.Chat().Service.Create(context.Background(), nil)
	if e != nil {
		t.Fatal(e)
	}
	if e = f.Chat().Service.Delete(context.Background(), session.ConversationID); e != nil {
		t.Fatal(e)
	}
	if e = f.Close(context.Background()); e != nil {
		t.Fatal(e)
	}
	for _, grant := range []struct{ priv, object string }{{"INSERT", "TABLE chat_run_event"}, {"DELETE", "TABLE agent_question"}, {"UPDATE", "TABLE agent_plan_step"}, {"INSERT", "TABLE token_usage"}, {"USAGE", "SEQUENCE chat_run_event_id_seq"}, {"USAGE", "SEQUENCE agent_followup_seq_seq"}} {
		appExec(t, admin, "REVOKE "+grant.priv+" ON "+grant.object+" FROM "+role)
		bad, err := open()
		if err == nil {
			_ = bad.Close(context.Background())
			t.Fatal("missing permission accepted", grant)
		}
		appExec(t, admin, "GRANT "+grant.priv+" ON "+grant.object+" TO "+role)
	}
	var active int
	_ = admin.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		return c.QueryRowContext(context.Background(), `SELECT count(*) FROM pg_stat_activity WHERE application_name='go-p4-acl'`).Scan(&active)
	})
	if active != 0 {
		t.Fatal("failed startup leaked pools", active)
	}
}

func TestFoundationP4OriginalFrontendClientsAgainstGo(t *testing.T) {
	var ask atomic.Bool
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/audio/transcriptions" {
			fmt.Fprint(w, `{"text":"voice frontend"}`)
			return
		}
		var body struct {
			Messages []struct{ Role, Content string }
		}
		_ = json.NewDecoder(r.Body).Decode(&body)
		for _, m := range body.Messages {
			if m.Role == "user" && m.Content == "ask frontend" && !ask.Swap(true) {
				p4Tool(w, "askUser", `{"question":"选择下一步？","options":[{"label":"继续"},{"label":"停止"}]}`)
				return
			}
		}
		for _, m := range body.Messages {
			if m.Role == "user" && m.Content == "stop frontend" {
				w.Header().Set("Content-Type", "text/event-stream")
				w.WriteHeader(200)
				w.(http.Flusher).Flush()
				<-r.Context().Done()
				return
			}
		}
		p4Text(w, "frontend Go reply")
	}))
	defer upstream.Close()
	f := p4Foundation(t, nil, upstream)
	appExec(t, f, `DELETE FROM ai_model_profile;DELETE FROM ai_model_config`)
	defer appExec(t, f, `DELETE FROM ai_model_profile;DELETE FROM ai_model_config`)
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "node", "--import", "../scripts/migration/frontend/loader.mjs", "../scripts/migration/frontend/go-p4.mjs")
	cmd.Dir = "../../../frontend"
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "HOME=" + t.TempDir(), "GO_P4_BASE_URL=" + server.URL + "/api/v1", "NO_PROXY=127.0.0.1,localhost"}
	output, e := cmd.CombinedOutput()
	if e != nil {
		t.Fatalf("actual frontend clients: %v\n%s", e, output)
	}
	t.Log(string(output))
}
