//go:build integration

package model

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

func fixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需要原迁移创建的独立测试库")
	}
	opts := database.DefaultPostgresOptions()
	opts.Host = os.Getenv("GO_TEST_POSTGRES_HOST")
	opts.Database = os.Getenv("GO_TEST_POSTGRES_DB")
	opts.Username = os.Getenv("GO_TEST_POSTGRES_USER")
	opts.Password = os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || opts.Host != "127.0.0.1" || opts.Database != "personalos_go_test" || opts.Username != "go_fixture" {
		t.Fatal("仅允许临时独立测试库")
	}
	opts.Port = port
	opts.SSLMode = os.Getenv("GO_TEST_POSTGRES_SSL_MODE")
	opts.RootCert = os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(opts)
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

func TestP3ConfigurationReadFromOriginalSchema(t *testing.T) {
	pool := fixturePool(t)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	keys := fixtureKey(t, 1)
	store, err := NewPostgresReadStore(pool, keys)
	if err != nil {
		t.Fatal(err)
	}
	r := resolver(t, nil, map[string]string{"OPENAI_CHAT_MODEL": "env-fixture-chat"})
	service, err := NewReadService(store, r)
	if err != nil {
		t.Fatal(err)
	}
	exec := func(query string, args ...any) {
		t.Helper()
		if err := pool.WithConn(ctx, func(c *sql.Conn) error { _, err := c.ExecContext(ctx, query, args...); return err }); err != nil {
			t.Fatal(err)
		}
	}
	exec(`DELETE FROM ai_model_profile; DELETE FROM ai_model_config`)
	t.Cleanup(func() {
		_ = pool.WithConn(context.Background(), func(c *sql.Conn) error {
			_, err := c.ExecContext(context.Background(), `DELETE FROM ai_model_profile; DELETE FROM ai_model_config`)
			return err
		})
	})
	current, err := service.Current(ctx)
	if err != nil || current.ChatModel != "env-fixture-chat" || current.Revision != nil {
		t.Fatal("empty database fallback failed")
	}
	empty, err := service.Profiles(ctx)
	if err != nil {
		t.Fatal(err)
	}
	encoded, err := json.Marshal(empty)
	if err != nil || string(encoded) != `{"profiles":[],"activeId":null}` {
		t.Fatal("empty list contract mismatch")
	}
	// 旧明文、V20 回落与修订时间均从原表读取，不伪造另一个 schema。
	now := time.Date(2026, 11, 3, 6, 0, 0, 123000000, time.UTC)
	exec(`INSERT INTO ai_model_config(id,base_url,api_key,chat_model,temperature,revision,updated_at) VALUES(1,'https://legacy.invalid','legacy-fixture-only-abcd','legacy-chat',0.3,7,$1)`, now)
	legacy, found, err := store.Legacy(ctx)
	if err != nil || !found || legacy.Revision == nil || *legacy.Revision != 7 || !legacy.UpdatedAt.Equal(now) {
		t.Fatal("legacy mapping failed")
	}
	current, err = service.Current(ctx)
	if err != nil || current.ChatModel != "legacy-chat" || current.APIKey() != "legacy-fixture-only-abcd" {
		t.Fatal("legacy current fallback failed")
	}
	const a = "00000000-0000-0000-0000-000000000001"
	const b = "00000000-0000-0000-0000-000000000002"
	const c = "00000000-0000-0000-0000-000000000003"
	ciphertext, err := keys.Encrypt(ptr("synthetic-profile-only-wxyz"))
	if err != nil {
		t.Fatal(err)
	}
	// 插入顺序与展示顺序相反，sort_order 相同时必须用 created_at 排序。
	exec(`INSERT INTO ai_model_profile(id,name,api_key,chat_model,embedding_model,active,sort_order,revision,created_at,updated_at,protocol,audio_model)
 VALUES($1,'last',$2,'default-last','legacy-embed',false,20,3,$4,$4,'ANTHROPIC',NULL),
       ($3,'active',$2,'default-active','legacy-embed',true,10,4,$4+interval '1 second',$4,'OLLAMA','asr-active'),
       ($5,'first',NULL,' old-chat ',' old-embed ',false,10,0,$4,$4,'OPENAI','old-asr')`, a, *ciphertext, b, now, c)
	exec(`UPDATE ai_model_profile SET temperature=0.25,preset_id='fixture-preset' WHERE id=$1`, b)
	// 相同 sort_order 的模型按 id 升序；用途排序由原字符串 kind 决定。
	exec(`INSERT INTO ai_model_profile_model(profile_id,kind,model_name,sort_order,context_window)
 VALUES($1,'CHAT','position-2',20,32768),($1,'CHAT','position-1',10,NULL),($1,'CHAT','position-1b',10,8192),
       ($1,'AUDIO','asr-active',0,NULL),($1,'EMBEDDING','embed-active',0,NULL),($2,'AUDIO','audio-only',0,NULL)`, b, a)
	profiles, err := service.Profiles(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(profiles.Profiles) != 3 || profiles.Profiles[0].ID != c || profiles.Profiles[1].ID != b || profiles.Profiles[2].ID != a || profiles.ActiveID == nil || *profiles.ActiveID != b {
		t.Fatal("profile ordering/activeId mismatch")
	}
	active := profiles.Profiles[1]
	if active.Temperature == nil || *active.Temperature != 0.25 || active.PresetID == nil || *active.PresetID != "fixture-preset" {
		t.Fatal("non-null profile scalar mapping failed")
	}
	if active.Protocol != "OLLAMA" || active.APIKeyMasked != "****wxyz" || active.Revision != 4 || active.AudioModel != "asr-active" || strings.Join(active.ChatModels, ",") != "position-1,position-1b,position-2" || len(active.ContextWindows) != 2 || active.ContextWindows["position-2"] != 32768 {
		t.Fatal("profile/model mapping mismatch")
	}
	if strings.Join(profiles.Profiles[0].ChatModels, ",") != "old-chat" || len(profiles.Profiles[0].AudioModels) != 0 || len(profiles.Profiles[2].ChatModels) != 0 || len(profiles.Profiles[2].EmbeddingModels) != 0 {
		t.Fatal("all-or-nothing legacy list fallback mismatch")
	}
	current, err = service.Current(ctx)
	if err != nil || current.ChatModel != "default-active" || current.APIKey() != "synthetic-profile-only-wxyz" || !current.HasAudioModel() {
		t.Fatal("active profile resolution failed")
	}
	// 兼容单行入口仍读 V20，不能被 active 档案悄悄替换。
	oldDTO, err := service.Config(ctx)
	if err != nil || oldDTO.ChatModel != "legacy-chat" || oldDTO.APIKeyMasked != "****abcd" || oldDTO.Temperature == nil || *oldDTO.Temperature != 0.3 {
		t.Fatal("legacy endpoint resolution changed")
	}
	encoded, err = json.Marshal(profiles)
	if err != nil || strings.Contains(string(encoded), "synthetic-profile-only") || strings.Contains(string(encoded), *ciphertext) {
		t.Fatal("read DTO leaked key")
	}
	var storedKey string
	var updated time.Time
	var count int
	err = pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT api_key,updated_at,(SELECT count(*) FROM ai_model_profile_model) FROM ai_model_profile WHERE id=$1`, b).Scan(&storedKey, &updated, &count)
	})
	if err != nil || storedKey != *ciphertext || !updated.Equal(now) || count != 6 {
		t.Fatal("read operation mutated persisted state")
	}
	// 保存原 Java 转换器实际产生的密文，再通过同一真实仓储解析。
	fixtureData, err := os.ReadFile("testdata/java-config-read.json")
	if err != nil {
		t.Fatal(err)
	}
	var javaFixture struct {
		Ciphertext string `json:"javaCiphertext"`
		Plaintext  string `json:"syntheticPlaintext"`
	}
	if err := json.Unmarshal(fixtureData, &javaFixture); err != nil || javaFixture.Ciphertext == "" {
		t.Fatal("missing Java ciphertext fixture")
	}
	exec(`UPDATE ai_model_profile SET api_key=$1 WHERE id=$2`, javaFixture.Ciphertext, b)
	current, err = service.Current(ctx)
	if err != nil || current.APIKey() != javaFixture.Plaintext {
		t.Fatal("persisted Java ciphertext could not be resolved")
	}
	// 当前读取不得解密无关档案或旧行；列表遇到错误则整份失败，不泄漏半份结果。
	exec(`UPDATE ai_model_config SET api_key='{aesgcm:v1}corrupt'; UPDATE ai_model_profile SET api_key='{aesgcm:v1}corrupt' WHERE id='00000000-0000-0000-0000-000000000001'`)
	if _, err := service.Current(ctx); err != nil {
		t.Fatal("current tried to decode inactive/legacy key")
	}
	if result, err := store.Profiles(ctx); err == nil || result != nil {
		t.Fatal("corrupt key returned partial profile list")
	}
	if value, found, err := store.Legacy(ctx); err == nil || found || value.APIKey != nil {
		t.Fatal("corrupt legacy key returned partial data")
	}
	exec(`UPDATE ai_model_profile SET active=false WHERE active; UPDATE ai_model_config SET api_key='legacy-fixture-only-abcd'`)
	current, err = service.Current(ctx)
	if err != nil || current.ChatModel != "legacy-chat" {
		t.Fatal("deactivation did not refresh current")
	}
	disabled, _ := NewKeyCodec("")
	noKey, _ := NewPostgresReadStore(pool, disabled)
	if _, err := noKey.Profiles(ctx); !errors.Is(err, errKeyRequired) {
		t.Fatal("encrypted rows accepted without master key")
	}
	cancelled, stop := context.WithCancel(context.Background())
	stop()
	if _, err := service.Current(cancelled); !errors.Is(err, context.Canceled) {
		t.Fatal("database read did not honor cancellation")
	}
}
