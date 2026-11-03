package config

import (
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// 基础用例来自固定 Java 基线 DotEnvParserTest 的字面量断言；
// MCP relaxed binding 尚未实现，不能据此标记整个 Java 测试文件 verified。
func TestParseDotEnvJavaContract(t *testing.T) {
	for _, test := range []struct {
		name string
		text string
		want map[string]string
	}{
		{"simplePairs", "# 这是注释\n\nPOSTGRES_DB=personal_ai_os\nPOSTGRES_PORT=55432\n   \nOPENAI_CHAT_MODEL=qwen3.8-flash", map[string]string{"POSTGRES_DB": "personal_ai_os", "POSTGRES_PORT": "55432", "OPENAI_CHAT_MODEL": "qwen3.8-flash"}},
		{"equalsAndColon", "DATABASE_URL=jdbc:postgresql://localhost:55432/db?a=b\nSIGNATURE=abc==", map[string]string{"DATABASE_URL": "jdbc:postgresql://localhost:55432/db?a=b", "SIGNATURE": "abc=="}},
		{"inlineCommentIsLiteral", "KEY=abc#def", map[string]string{"KEY": "abc#def"}},
		{"pairedQuotesOnly", "DOUBLE=\"hello world\"\nSINGLE='hello world'\nUNPAIRED=\"oops\nMISMATCHED='a\"", map[string]string{"DOUBLE": "hello world", "SINGLE": "hello world", "UNPAIRED": "\"oops", "MISMATCHED": "'a\""}},
		{"export", "export OPENAI_API_KEY=fixture-only", map[string]string{"OPENAI_API_KEY": "fixture-only"}},
		{"malformed", "JUST_A_WORD\n=novalue\n\n# 注释\nOK=1", map[string]string{"OK": "1"}},
		{"explicitEmpty", "OPENAI_API_KEY=", map[string]string{"OPENAI_API_KEY": ""}},
		{"noExpansionOrUnescaping", "KEY='${HOME}\\n#x'\nDOUBLE=\"$KEY\\\"x\"", map[string]string{"KEY": "${HOME}\\n#x", "DOUBLE": "$KEY\\\"x"}},
		{"lineTerminators", "A=1\rB=2\r\nC=3\nD=4", map[string]string{"A": "1", "B": "2", "C": "3", "D": "4"}},
	} {
		t.Run(test.name, func(t *testing.T) {
			source, err := ParseDotEnv([]byte(test.text))
			if err != nil || !reflect.DeepEqual(source.values, test.want) {
				t.Fatalf("解析结果与 Java 字面量断言不符；error=%v", err)
			}
		})
	}
}

func TestDotEnvJavaWhitespaceAndOrderedDuplicate(t *testing.T) {
	for _, blank := range []string{"\u001c", "\u001f", "\u1680", "\u2000", "\u200a", "\u2028", "\u2029", "\u205f", "\u3000"} {
		source, err := ParseDotEnv([]byte(blank + " K " + blank + "=" + blank + "value" + blank))
		value, ok := source.Lookup("K")
		if err != nil || !ok || value != "value" {
			t.Errorf("Java 空白 U+%04X 应被 strip", []rune(blank)[0])
		}
	}
	for _, literal := range []string{"\u00a0", "\u0085", "\u2007", "\u202f", "\u0000"} {
		source, err := ParseDotEnv([]byte("KEY=" + literal + "value" + literal))
		value, ok := source.Lookup("KEY")
		if err != nil || !ok || value != literal+"value"+literal {
			t.Errorf("Java 非 strip 字符 U+%04X 必须保留", []rune(literal)[0])
		}
	}
	source, err := ParseDotEnv([]byte("K=first\nOTHER=x\nK=second"))
	if err != nil || !reflect.DeepEqual(source.Keys(), []string{"K", "OTHER"}) {
		t.Fatal("重复键必须保留第一次插入顺序")
	}
	if value, _ := source.Lookup("K"); value != "second" {
		t.Fatal("重复键必须最后赋值获胜")
	}
	keys := source.Keys()
	keys[0] = "mutated"
	if source.Keys()[0] != "K" {
		t.Fatal("返回的 key 列表不能修改解析结果")
	}
}

func TestDotEnvInvalidUTF8AndSafeFormatting(t *testing.T) {
	if _, err := ParseDotEnv([]byte{'K', '=', 0xff}); err == nil {
		t.Fatal("不能容忍无效 UTF-8 并加载部分凭据")
	}
	source, err := ParseDotEnv([]byte("KEY=fixture-private-value"))
	if err != nil {
		t.Fatal(err)
	}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, source), "fixture-private-value") {
			t.Fatal("格式化 DotEnv 不得泄露值")
		}
	}
}

func writeDotEnv(t *testing.T, dir, text string) {
	t.Helper()
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte(text), 0600); err != nil {
		t.Fatal(err)
	}
}

func TestEnvironmentDefaultAndExplicitDisableNeverLoadLocalSecrets(t *testing.T) {
	dir := t.TempDir()
	writeDotEnv(t, dir, "GO_DOTENV_ENABLED=true\nPRIVATE_TEST_KEY=fixture-private-value")
	for _, variables := range []map[string]string{
		nil,
		{"GO_DOTENV_ENABLED": "false"},
		{"GO_DOTENV_ENABLED": "true", "APP_DOTENV_ENABLED": "false"},
	} {
		env, err := LoadEnvironment(environment(variables), dir)
		if err != nil || env.Status() != "disabled" || len(env.DotEnvKeys()) != 0 {
			t.Fatalf("禁止默认读取 .env：%v，%v", env, err)
		}
		if _, found := env.Lookup("PRIVATE_TEST_KEY"); found {
			t.Fatal("禁用读取时不能加载文件里的凭据")
		}
	}
}

func TestEnvironmentNearestFileSystemPriorityAndEmptyOverride(t *testing.T) {
	dir := t.TempDir()
	writeDotEnv(t, dir, "LOCAL=from-parent\nPARENT_ONLY=parent")
	child := filepath.Join(dir, "backend")
	if err := os.Mkdir(child, 0700); err != nil {
		t.Fatal(err)
	}
	writeDotEnv(t, child, "LOCAL=from-child\nEMPTY=file\nKEY=fixture-private-value\nMCP_SERVERS_FETCH_COMMAND=uvx")
	env, err := LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true", "LOCAL": "system", "EMPTY": ""}), child)
	if err != nil || env.Status() != "loaded" {
		t.Fatalf("显式读取最近文件失败：%v，%v", env, err)
	}
	for key, want := range map[string]string{"LOCAL": "system", "EMPTY": "", "MCP_SERVERS_FETCH_COMMAND": "uvx"} {
		if value, ok := env.Lookup(key); !ok || value != want {
			t.Errorf("环境优先级/动态键 %s 不正确", key)
		}
	}
	if _, found := env.Lookup("PARENT_ONLY"); found {
		t.Fatal("只读取最近一份文件，不合并父文件")
	}
	for _, format := range []string{"%v", "%+v", "%#v"} {
		if strings.Contains(fmt.Sprintf(format, env), "fixture-private-value") {
			t.Fatal("格式化 Environment 不得泄露值")
		}
	}
}

func TestEnvironmentParentLookupBoundAndUnreadableFallback(t *testing.T) {
	dir := t.TempDir()
	writeDotEnv(t, dir, "KEY=from-parent")
	deep := dir
	for depth := 1; depth <= 4; depth++ {
		deep = filepath.Join(deep, "child")
		if err := os.Mkdir(deep, 0700); err != nil {
			t.Fatal(err)
		}
		env, err := LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true"}), deep)
		want := "loaded"
		if depth == 4 {
			want = "not_found"
		}
		if err != nil || env.Status() != want {
			t.Errorf("向上查找 %d 层不正确：%v，%v", depth, env, err)
		}
	}
	if err := os.WriteFile(filepath.Join(deep, ".env"), []byte{'K', '=', 0xff}, 0600); err != nil {
		t.Fatal(err)
	}
	env, err := LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true"}), deep)
	if err != nil || env.Status() != "unreadable" {
		t.Fatalf("无效文件应被忽略而不拖垮启动：%v，%v", env, err)
	}
	if _, found := env.Lookup("KEY"); found {
		t.Fatal("最近文件失败后不能悄悄换用另一份父文件")
	}
}

func TestEnvironmentInvalidSettingsAreSanitized(t *testing.T) {
	if _, err := LoadEnvironment(nil, ""); err == nil {
		t.Fatal("nil 来源应被明确拒绝")
	}
	for _, key := range []string{"GO_DOTENV_ENABLED", "APP_DOTENV_ENABLED"} {
		variables := map[string]string{"GO_DOTENV_ENABLED": "true", key: "fixture-private-value"}
		_, err := LoadEnvironment(environment(variables), t.TempDir())
		if err == nil || strings.Contains(err.Error(), "fixture-private-value") || !strings.Contains(err.Error(), key) {
			t.Fatal("无效布尔值应报错且不能回显输入")
		}
	}
}

func TestDotEnvStaysBelowStaticProfileConfiguration(t *testing.T) {
	dir := t.TempDir()
	writeDotEnv(t, dir, "SPRING_PROFILES_ACTIVE=worker-index\nAPP_RUNTIME_ROLE=api\nSERVER_PORT=18081")
	env, err := LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true"}), dir)
	if err != nil {
		t.Fatal(err)
	}
	settings, err := env.Config()
	if err != nil || settings.Role != Index || settings.Port != 18081 {
		t.Fatalf("静态 profile role 应覆盖 .env；端口占位符仍读 .env：%v，%v", settings, err)
	}
	env, err = LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true", "APP_RUNTIME_ROLE": "review"}), dir)
	if err != nil {
		t.Fatal(err)
	}
	settings, err = env.Config()
	if err != nil || settings.Role != Review {
		t.Fatalf("系统环境应覆盖静态 profile：%v，%v", settings, err)
	}
	writeDotEnv(t, dir, "SPRING_PROFILES_ACTIVE=prod\nAUTH_ENABLED=false\nAUTH_PASSWORD=fixture-password-16\nPOSTGRES_PASSWORD=fixture-db\nSERVER_SERVLET_SESSION_COOKIE_SECURE=true")
	env, err = LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true"}), dir)
	if err != nil {
		t.Fatal(err)
	}
	security, err := loadSecurity(env.Lookup, env.system, true)
	if err != nil || !security.AuthEnabled || security.ValidateProduction() != nil {
		t.Fatalf("prod 静态安全开关不能被低优先级 .env 关闭：%v，%v", security, err)
	}
}
