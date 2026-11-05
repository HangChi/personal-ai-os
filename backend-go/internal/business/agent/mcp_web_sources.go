package agent

import (
	"encoding/json"
	"regexp"
	"strings"

	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

var fetchedPage = regexp.MustCompile(`(?m)^Contents of (https?://[^\s]+):\r?\n`)

func mcpWebSources(tool string, args map[string]any, result *sdk.CallToolResult) []WebSource {
	if result == nil || result.IsError {
		return nil
	}
	if tool == "fetch" {
		requested, _ := args["url"].(string)
		for _, item := range result.Content {
			text, ok := item.(*sdk.TextContent)
			if !ok {
				continue
			}
			header := fetchedPage.FindStringSubmatchIndex(text.Text)
			if header == nil || strings.TrimSuffix(requested, "/") != strings.TrimSuffix(text.Text[header[2]:header[3]], "/") {
				continue
			}
			body, _, _ := strings.Cut(text.Text[header[1]:], "\n\n<error>Content truncated.")
			if strings.HasPrefix(strings.TrimSpace(body), "<error>") {
				continue
			}
			if source, ok := newWebSource("", requested, body); ok {
				return []WebSource{source}
			}
		}
		return nil
	}
	switch tool {
	case "web_search", "search_web", "brave_web_search", "tavily_search", "tavily-search":
	default:
		return nil
	}
	var payload struct {
		Results []struct {
			Title       string
			URL         string
			Content     string
			Snippet     string
			Description string
		}
	}
	if raw, err := json.Marshal(result.StructuredContent); err == nil && len(raw) <= 1<<20 {
		_ = json.Unmarshal(raw, &payload)
	}
	if len(payload.Results) == 0 {
		for _, item := range result.Content {
			if text, ok := item.(*sdk.TextContent); ok && len(text.Text) <= 1<<20 &&
				json.Unmarshal([]byte(text.Text), &payload) == nil && len(payload.Results) > 0 {
				break
			}
		}
	}
	var sources []WebSource
	for _, item := range payload.Results {
		body := item.Content
		if Strip(body) == "" {
			body = item.Snippet
		}
		if Strip(body) == "" {
			body = item.Description
		}
		if source, ok := newWebSource(item.Title, item.URL, body); ok {
			sources = append(sources, source)
			if len(sources) == 5 {
				break
			}
		}
	}
	return sources
}
