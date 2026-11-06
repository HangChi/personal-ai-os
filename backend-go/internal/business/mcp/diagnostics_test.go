package mcp

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	"github.com/modelcontextprotocol/go-sdk/jsonrpc"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestMCPDiagnosticsPreserveReasonAndRedactCredentials(t *testing.T) {
	raw := "Refused https://alice:private-credential@example.com/path?token=private-query " +
		"Authorization: Bearer private-bearer API_KEY=private-key password='private password' " +
		"{\"access_token\":\"private-json\"}\n\x1b[31m"
	text := SanitizeDiagnostic(raw)
	if strings.Contains(text, "private-") || strings.Contains(text, "private password") || strings.ContainsAny(text, "\n\x1b") || !strings.Contains(text, "example.com/path") {
		t.Fatal("unsafe diagnostic", text)
	}
	if got := SanitizeDiagnostic(strings.Repeat("字", 5000)); len([]rune(got)) != 1201 || !strings.HasSuffix(got, "…") {
		t.Fatal("diagnostic was not bounded")
	}
	result := &sdk.CallToolResult{IsError: true, Content: []sdk.Content{&sdk.TextContent{Text: "Access denied - path outside allowed directories: /docs not in /sandbox"}}}
	data, _ := json.Marshal(ToolErrorData("filesystem", "search_files", result))
	for _, want := range []string{"TOOL_ERROR", "outside allowed directories", "/sandbox", `"untrusted":true`} {
		if !strings.Contains(string(data), want) {
			t.Fatal(string(data), want)
		}
	}
	data, _ = json.Marshal(ToolErrorData("fetch", "fetch", nil))
	if !strings.Contains(string(data), "未提供文本原因") {
		t.Fatal(string(data))
	}
}

func TestMCPDiagnosticsDistinguishProtocolAndTransport(t *testing.T) {
	err := fmt.Errorf("wrapper: %w", &jsonrpc.Error{Code: -32602, Message: "Invalid arguments: path is required"})
	protocol := protocolCallError(err)
	if protocol == nil {
		t.Fatal("wrapped JSON-RPC rejection was not recognized")
	}
	data, _ := json.Marshal(CallErrorData("files", "search_files", protocol))
	for _, want := range []string{"PROTOCOL_ERROR", "-32602", "path is required"} {
		if !strings.Contains(string(data), want) {
			t.Fatal(string(data))
		}
	}
	err = fmt.Errorf("private-command-secret")
	data, _ = json.Marshal(CallErrorData("files", "search_files", err))
	if protocolCallError(err) != nil || strings.Contains(string(data), "private-command-secret") || !strings.Contains(string(data), "CONNECTION_ERROR") {
		t.Fatal(string(data))
	}
}
