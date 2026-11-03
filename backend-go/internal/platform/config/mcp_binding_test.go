package config

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestMCPDotEnvBindingOriginalScenario(t *testing.T) {
	dir := t.TempDir()
	writeDotEnv(t, dir, "MCP_ENABLED=true\nMCP_SERVERS_FETCH_COMMAND=uvx\nMCP_SERVERS_FETCH_ARGS=mcp-server-fetch\nMCP_SERVERS_FETCH_AUTO_TOOLS=fetch\nMCP_SERVERS_FETCH_CONNECT_TIMEOUT=30s\n")
	env, err := LoadEnvironment(environment(map[string]string{"GO_DOTENV_ENABLED": "true"}), dir)
	if err != nil {
		t.Fatal(err)
	}
	if env.Status() != "loaded" {
		t.Fatal("没有读取本次临时.env")
	}
	dotenv := env.dotenv
	properties, err := BindMCPProperties(dotenv.BindingSource())
	if err != nil || !properties.Enabled() || !reflect.DeepEqual(properties.ServerNames(), []string{"fetch"}) {
		t.Fatal("原.env动态server断言失败", err)
	}
	server, found := properties.Server("fetch")
	if !found || server.Command() == nil || *server.Command() != "uvx" ||
		!reflect.DeepEqual(server.Args(), []string{"mcp-server-fetch"}) ||
		!reflect.DeepEqual(server.AutoTools(), []string{"fetch"}) || server.ConnectTimeout() != 30*time.Second {
		t.Fatal("原.env的MCP字段绑定丢失")
	}
	// 系统覆盖只作用于相同字段；低优先级.env的args/工具仍保留。
	properties, err = BindMCPProperties(BindingSource{Environment: true, Values: map[string]string{"MCP_SERVERS_FETCH_COMMAND": "synthetic-high"}}, dotenv.BindingSource())
	if err != nil {
		t.Fatal(err)
	}
	server, _ = properties.Server("fetch")
	if *server.Command() != "synthetic-high" || !reflect.DeepEqual(server.Args(), []string{"mcp-server-fetch"}) {
		t.Fatal("来源优先级误删整个server")
	}
	source := dotenv.BindingSource()
	source.Values["MCP_SERVERS_FETCH_COMMAND"] = "changed-copy"
	if value, _ := dotenv.Lookup("MCP_SERVERS_FETCH_COMMAND"); value != "uvx" {
		t.Fatal("返回的来源map修改了.env")
	}
}

func TestMCPSecretsAndContainersDoNotEscape(t *testing.T) {
	input := map[string]string{"mcp.servers.[fixture-private-server].command": "fixture-private-command",
		"mcp.servers.[fixture-private-server].args":          "fixture-private-arg",
		"mcp.servers.[fixture-private-server].env.[API_KEY]": "fixture-private-key",
		"mcp.servers.[fixture-private-server].auto-tools":    "fixture-private-tool"}
	source := BindingSource{Values: input}
	properties, err := BindMCPProperties(source)
	if err != nil {
		t.Fatal(err)
	}
	server, found := properties.Server("fixture-private-server")
	if !found {
		t.Fatal("丢失server")
	}
	input["mcp.servers.[fixture-private-server].command"] = "mutated-input"
	*server.Command() = "mutated-command"
	server.Args()[0] = "mutated-args"
	server.AutoTools()[0] = "mutated-tools"
	server.Environment()["API_KEY"] = "mutated-key"
	properties.ServerNames()[0] = "mutated-name"
	copy, _ := properties.Server("fixture-private-server")
	if *copy.Command() != "fixture-private-command" || copy.Args()[0] != "fixture-private-arg" ||
		copy.AutoTools()[0] != "fixture-private-tool" || copy.Environment()["API_KEY"] != "fixture-private-key" {
		t.Fatal("容器或指针别名泄漏内部可变状态")
	}
	for _, value := range []any{source, &source, properties, &properties, server, &server} {
		for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
			if strings.Contains(fmt.Sprintf(format, value), "fixture-private") {
				t.Fatal("格式化泄漏MCP配置")
			}
		}
		encoded, err := json.Marshal(value)
		if err != nil || strings.Contains(string(encoded), "fixture-private") {
			t.Fatal("JSON泄漏MCP配置")
		}
	}
	for _, key := range []string{"mcp.enabled", "mcp.servers.[fixture-private-server].connect-timeout", "mcp.servers.[fixture-private-server].args[99999999999999999999999]"} {
		_, err := BindMCPProperties(BindingSource{Values: map[string]string{key: "fixture-private-value"}})
		if err == nil || strings.Contains(err.Error(), "fixture-private") {
			t.Fatal("错误回显动态键/值")
		}
	}
}

func TestMCPLargeAndConcurrentBinding(t *testing.T) {
	// 巨大索引不能按其数字分配数组，也不能越过连续索引检查。
	if _, err := BindMCPProperties(BindingSource{Values: map[string]string{"mcp.servers.fetch.args[2147483647]": "fixture-only"}}); err == nil {
		t.Fatal("巨大索引被接受")
	}
	for _, input := range []map[string]string{
		{"mcp.servers.fetch.command": string([]byte{0xff})},
		{"mcp.servers.[" + string([]byte{0xff}) + "].command": "fixture-only"},
		{"mcp.servers.fetch.env.[API_KEY]": string([]byte{0xff})},
	} {
		if _, err := BindMCPProperties(BindingSource{Values: input}); err == nil {
			t.Fatal("非法UTF-8静默改变配置")
		}
	}
	input := []BindingSource{{Environment: true, Values: map[string]string{"MCP_ENABLED": "true", "MCP_SERVERS_FETCH_COMMAND": "fixture-only", "MCP_SERVERS_FETCH_ARGS": "a,b", "MCP_SERVERS_FETCH_AUTO_TOOLS": "read,read,list", "MCP_SERVERS_FETCH_ENV_API_KEY": "fixture-private"}}}
	var workers sync.WaitGroup
	for range 100 {
		workers.Go(func() {
			for range 10 {
				properties, err := BindMCPProperties(input...)
				if err != nil {
					t.Error(err)
					return
				}
				server, _ := properties.Server("fetch")
				if !properties.Enabled() || len(server.Args()) != 2 || len(server.AutoTools()) != 2 || server.Environment()["api.key"] != "fixture-private" {
					t.Error("并发绑定串扰")
					return
				}
				server.Environment()["api.key"] = "local-change"
			}
		})
	}
	workers.Wait()
}
