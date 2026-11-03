//go:build integration

package app

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

func TestFoundationP3CompositionSecurityEmbeddingMetricsAndShutdown(t *testing.T) {
	var modelCalls atomic.Int32
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		modelCalls.Add(1)
		switch r.URL.Path {
		case "/v1/embeddings":
			fmt.Fprint(w, `{"data":[{"index":0,"embedding":[1,2]}],"usage":{"prompt_tokens":2,"total_tokens":2}}`)
		case "/v1/chat/completions":
			fmt.Fprint(w, `{"choices":[{"message":{"content":"正常"}}]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer upstream.Close()
	values := map[string]string{"app.auth.enabled": "true", "app.auth.password": "fixture-password", "app.security.csrf-enabled": "true", "app.rate-limit.enabled": "false", "spring.ai.model.chat": "none", "spring.ai.model.embedding": "openai", "spring.ai.openai.base-url": upstream.URL, "spring.ai.openai.api-key": "synthetic-foundation-only-key", "app.embedding-cache.enabled": "true"}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	f, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p3-composition"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: true, EnableP3: true, ModelTransport: upstream.Client().Transport}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close(context.Background())
	appExec(t, f, `DELETE FROM ai_model_profile;DELETE FROM ai_model_config`)
	defer appExec(t, f, `DELETE FROM ai_model_profile;DELETE FROM ai_model_config`)
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	client := server.Client()
	code, _ := appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/profiles", "", `{"name":"must-not-write"}`)
	if code != 403 {
		t.Fatal("CSRF not enforced", code)
	}
	code, _ = appRequest(t, client, "GET", server.URL+"/api/v1/settings/model/profiles", "", "")
	if code != 401 {
		t.Fatal("authentication bypass", code)
	}
	jar, _ := cookiejar.New(nil)
	client.Jar = jar
	code, body := appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	if code != 200 {
		t.Fatal(code)
	}
	token := body["data"].(map[string]any)["token"].(string)
	code, _ = appRequest(t, client, "POST", server.URL+"/api/v1/auth/login", token, `{"username":"admin","password":"fixture-password"}`)
	if code != 200 {
		t.Fatal(code)
	}
	_, body = appRequest(t, client, "GET", server.URL+"/api/v1/auth/csrf", "", "")
	token = body["data"].(map[string]any)["token"].(string)
	profile := fmt.Sprintf(`{"name":"active","baseUrl":%q,"apiKey":"synthetic-foundation-only-key","chatModels":["fixture-chat"]}`, upstream.URL)
	code, body = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/profiles", token, profile)
	if code != 200 {
		t.Fatal(code, body)
	}
	id := body["data"].(map[string]any)["id"].(string)
	code, _ = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/profiles/"+id+"/activate", token, "")
	if code != 200 || !f.Models().Runtime.Configured() {
		t.Fatal("active runtime not applied", code)
	}
	code, body = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/test", token, "")
	if code != 200 || body["data"].(map[string]any)["ok"] != true {
		t.Fatal("model test through protected chain", code, body)
	}
	for i := 0; i < 2; i++ {
		if _, e = f.Models().Embedding.Embed(context.Background(), model.EmbeddingRequest{Input: []string{"same private text"}}); e != nil {
			t.Fatal(e)
		}
	}
	if modelCalls.Load() != 2 {
		t.Fatal("cache not applied", modelCalls.Load())
	}
	code, body = appRequest(t, client, "GET", server.URL+"/actuator/metrics/personalos.embedding.cache.requests", "", "")
	if code != 200 {
		t.Fatal("embedding metrics not mounted", code, body)
	}
	metrics := body["measurements"].([]any)
	if metrics[0].(map[string]any)["value"] != float64(2) {
		t.Fatal("metrics wrong", body)
	}
	response, e := client.Get(server.URL + "/readyz")
	if e != nil {
		t.Fatal(e)
	}
	_ = response.Body.Close()
	if response.StatusCode != 503 {
		t.Fatal("P3 bypassed migration readyz gate")
	}
	code, body = appRequest(t, client, "GET", server.URL+"/api/v1/health", "", "")
	if code != 200 || body["data"].(map[string]any)["components"].(map[string]any)["ai"] != "CONFIGURED" {
		t.Fatal("runtime not reflected in health")
	}
	code, _ = appRequest(t, client, "GET", server.URL+"/api/v1/tasks", "", "")
	if code != 200 {
		t.Fatal("P2 route lost", code)
	}
	encoded, _ := json.Marshal(body)
	if strings.Contains(string(encoded), "synthetic-foundation-only-key") {
		t.Fatal("key leaked")
	}
}
func TestFoundationP3WorkerIsolationAndRepairableStartup(t *testing.T) {
	values := map[string]string{"app.runtime.role": "index", "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none"}
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if e != nil {
		t.Fatal(e)
	}
	f, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p3-worker"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP3: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer f.Close(context.Background())
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/settings/model/profiles", nil))
	if w.Code != 404 || w.Body.Len() != 0 {
		t.Fatal("worker model routes exposed")
	}
	appExec(t, f, `DELETE FROM ai_model_config;INSERT INTO ai_model_config(id,api_key,revision,updated_at) VALUES(1,'{aesgcm:v1}corrupt',1,now())`)
	defer appExec(t, f, `DELETE FROM ai_model_config`)
	values["app.runtime.role"] = "api"
	p, _ = config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	f2, e := OpenFoundation(context.Background(), p, FoundationOptions{Connector: fixtureAppConnector(t, "go-p3-repairable"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP3: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal("bad model config prevented repair startup", e)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = f2.Close(ctx)
	}()
	if f2.Models().Runtime.Configured() {
		t.Fatal("corrupt key invented a working model")
	}
}

func TestFoundationP3LeastPrivilegeAndMissingSequenceRejection(t *testing.T) {
	admin := fixtureFoundation(t, nil, nil)
	var secret [24]byte
	if _, err := rand.Read(secret[:]); err != nil {
		t.Fatal(err)
	}
	password := hex.EncodeToString(secret[:])
	role := "go_p3_" + password[:12]
	appExec(t, admin, "CREATE ROLE "+role+" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '"+password+"'")
	t.Cleanup(func() { appExec(t, admin, "DROP OWNED BY "+role+";DROP ROLE "+role) })
	appExec(t, admin, `GRANT CONNECT ON DATABASE personalos_go_test TO `+role+`;
 GRANT USAGE ON SCHEMA public TO `+role+`;
 GRANT SELECT ON flyway_schema_history TO `+role+`;
 GRANT SELECT,INSERT,UPDATE ON runtime_rate_bucket,runtime_lease TO `+role+`;
 GRANT SELECT,INSERT,UPDATE,DELETE ON ai_model_config,ai_model_profile,ai_model_profile_model TO `+role+`;
 GRANT USAGE ON SEQUENCE ai_model_profile_model_id_seq TO `+role+`;
 DELETE FROM ai_model_profile;DELETE FROM ai_model_config;`)
	defer appExec(t, admin, `DELETE FROM ai_model_profile;DELETE FROM ai_model_config`)
	opts := appPostgresOptions(t)
	opts.Username = role
	opts.Password = password
	opts.ApplicationName = "go-p3-acl"
	connector, err := database.NewPostgresConnector(opts)
	if err != nil {
		t.Fatal(err)
	}
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"spring.ai.model.chat": "none", "spring.ai.model.embedding": "none", "app.rate-limit.enabled": "false"}})
	if err != nil {
		t.Fatal(err)
	}
	open := func() (*Foundation, error) {
		return OpenFoundation(context.Background(), p, FoundationOptions{Connector: connector, Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP3: true}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	}
	f, err := open()
	if err != nil {
		t.Fatal("minimal P3 role rejected", err)
	}
	defer f.Close(context.Background())
	name, chat := "restricted", "gpt-4o"
	profile, err := f.Models().Service.Create(context.Background(), model.ProfileCommand{Name: &name, ChatModels: []*string{&chat}})
	if err != nil {
		t.Fatal("minimal role cannot write ordered model list", err)
	}
	if _, err = f.Models().Service.Activate(context.Background(), profile.ID); err != nil {
		t.Fatal(err)
	}
	err = f.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		_, err := c.ExecContext(context.Background(), "SELECT 1 FROM knowledge_document LIMIT 0")
		return err
	})
	var state interface{ SQLState() string }
	if !errors.As(err, &state) || state.SQLState() != "42501" {
		t.Fatal("P3 role can access unrelated knowledge table", err)
	}
	if err = f.Close(context.Background()); err != nil {
		t.Fatal(err)
	}
	appExec(t, admin, "REVOKE USAGE ON SEQUENCE ai_model_profile_model_id_seq FROM "+role)
	if failed, err := open(); err == nil || failed != nil {
		t.Fatal("missing model sequence privilege not rejected")
	}
	appExec(t, admin, "REVOKE INSERT ON ai_model_profile FROM "+role)
	if failed, err := open(); err == nil || failed != nil {
		t.Fatal("missing model write privilege not rejected")
	}
	err = admin.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		var n int
		err := c.QueryRowContext(context.Background(), "SELECT count(*) FROM pg_stat_activity WHERE application_name='go-p3-acl'").Scan(&n)
		if err == nil && n != 0 {
			return errors.New("P3 failed startup leaked connections")
		}
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
}
