package mcp

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode"

	"github.com/modelcontextprotocol/go-sdk/jsonrpc"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

var diagnosticURLCredentials = regexp.MustCompile(`(?i)(https?://)[^\s/@]+@+`)
var diagnosticURLQuery = regexp.MustCompile(`(?i)(https?://[^\s?#]+)[?#][^\s]*`)
var diagnosticAuth = regexp.MustCompile(`(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+`)
var diagnosticSecret = regexp.MustCompile(`(?i)(["']?(?:authorization|proxy-authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|cookie)["']?\s*[:=]\s*)(?:"[^"]*(?:"|$)|'[^']*(?:'|$)|[^\s,;}]+)`)

// CallError carries a bounded diagnostic without treating remote errors as proof of rollback.
type CallError struct {
	Kind, Detail string
}

func (e *CallError) Error() string { return e.Detail }

func SanitizeDiagnostic(raw string) string {
	value := []rune(raw)
	if len(value) > 4096 {
		value = value[:4096]
	}
	text := diagnosticURLCredentials.ReplaceAllString(string(value), "${1}[redacted]@")
	text = diagnosticURLQuery.ReplaceAllString(text, "${1}?[redacted]")
	text = diagnosticAuth.ReplaceAllString(text, "${1} [redacted]")
	text = diagnosticSecret.ReplaceAllString(text, "${1}[redacted]")
	text = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) || unicode.Is(unicode.Cf, r) {
			return ' '
		}
		return r
	}, text)
	text = strings.Join(strings.Fields(text), " ")
	value = []rune(text)
	if len(value) > 1200 {
		return string(value[:1200]) + "…"
	}
	return text
}

func diagnosticData(server, tool, kind, detail string) map[string]any {
	detail = SanitizeDiagnostic(detail)
	if detail == "" {
		detail = "远端工具报告失败，未提供文本原因。"
	}
	return map[string]any{"mcpError": map[string]any{"server": server, "tool": tool, "kind": kind, "detail": detail, "untrusted": true}}
}

func ToolErrorData(server, tool string, result *sdk.CallToolResult) map[string]any {
	var text strings.Builder
	count := 0
	if result != nil {
		for _, content := range result.Content {
			if item, ok := content.(*sdk.TextContent); ok && item != nil {
				if text.Len() > 0 {
					text.WriteByte('\n')
					count++
				}
				runes := []rune(item.Text)
				if len(runes) > 4096-count {
					runes = runes[:4096-count]
				}
				text.WriteString(string(runes))
				count += len(runes)
				if count >= 4096 {
					break
				}
			}
		}
	}
	return diagnosticData(server, tool, "TOOL_ERROR", text.String())
}

func CallErrorData(server, tool string, err error) map[string]any {
	var call *CallError
	if errors.As(err, &call) {
		return diagnosticData(server, tool, call.Kind, call.Detail)
	}
	return diagnosticData(server, tool, "CONNECTION_ERROR", "MCP 连接中断或调用超时，请检查服务状态。未自动重试。")
}

func protocolCallError(err error) *CallError {
	var rpc *jsonrpc.Error
	if errors.As(err, &rpc) {
		return &CallError{Kind: "PROTOCOL_ERROR", Detail: SanitizeDiagnostic(fmt.Sprintf("JSON-RPC %d: %s", rpc.Code, rpc.Message))}
	}
	return nil
}
