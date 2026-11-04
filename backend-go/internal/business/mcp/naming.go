// Package mcp owns external MCP connections and the explicit read-only server.
package mcp

import (
	"encoding/json"
	"regexp"
	"slices"
	"strings"
)

var invalidName = regexp.MustCompile(`[^a-z0-9_]+`)
var underscores = regexp.MustCompile(`_+`)

func PrefixedName(server, tool string) string {
	clean := func(s string) string {
		s = strings.Trim(underscores.ReplaceAllString(invalidName.ReplaceAllString(strings.ToLower(s), "_"), "_"), "_")
		if s == "" {
			return "unknown"
		}
		return s
	}
	name := "mcp_" + clean(server) + "_" + clean(tool)
	if len(name) > 100 {
		name = name[:100]
	}
	return name
}

func Policy(original string, automatic []string) string {
	if slices.Contains(automatic, original) {
		return "AUTO"
	}
	return "CONFIRM"
}

func Conflicts(builtin, external []string) []string {
	out := []string{}
	seen := map[string]bool{}
	for _, name := range external {
		if slices.Contains(builtin, name) {
			out = append(out, name+"（与内置工具同名）")
		}
		if seen[name] {
			out = append(out, name+"（多个 MCP server 产生同名工具）")
		}
		seen[name] = true
	}
	return out
}

// Spring's MCP adapter normalizes an omitted root type to object.
func NormalizeSchema(value any) (map[string]any, bool) {
	raw, err := json.Marshal(value)
	if err != nil {
		return nil, false
	}
	var schema map[string]any
	if json.Unmarshal(raw, &schema) != nil || schema == nil {
		return nil, false
	}
	if schema["type"] == nil {
		schema["type"] = "object"
	}
	if schema["properties"] == nil {
		schema["properties"] = map[string]any{}
	}
	_, properties := schema["properties"].(map[string]any)
	return schema, schema["type"] == "object" && properties
}
