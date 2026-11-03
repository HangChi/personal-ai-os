package model

import (
	"net/url"
	"sort"
	"strings"
)

// 固定原 Java 目录，不自动在线更新，端点声明和手动值优先。
var knownWindows = map[string]int{
	"gpt-4.1":             1047576,
	"gpt-4o-mini":         128000,
	"gpt-4o":              128000,
	"gpt-4-turbo":         128000,
	"gpt-4-32k":           32768,
	"gpt-4":               8192,
	"gpt-3.5-turbo":       16385,
	"o3":                  200000,
	"o4-mini":             200000,
	"o1":                  200000,
	"claude-3-7":          200000,
	"claude-3-5":          200000,
	"claude-3":            200000,
	"claude-sonnet-4":     200000,
	"claude-opus-4":       200000,
	"qwen-long":           1000000,
	"qwen3-max":           262144,
	"qwen-max":            30720,
	"qwen-plus":           131072,
	"qwen-turbo":          131072,
	"qwen3":               131072,
	"qwen2.5":             131072,
	"qwen-vl-max":         30720,
	"deepseek-v3":         65536,
	"deepseek-chat":       65536,
	"deepseek-reasoner":   65536,
	"deepseek-coder":      65536,
	"deepseek-v4.1-flash": 1048576,
	"gemini-2.5":          1048576,
	"gemini-2.0":          1048576,
	"gemini-1.5-pro":      2097152,
	"gemini-1.5":          1048576,
	"llama-3.3":           131072,
	"llama-3.1":           131072,
	"llama-3":             8192,
	"mistral-large":       131072,
	"glm-4":               131072,
	"glm-4-plus":          131072,
	"moonshot-v1-128k":    131072,
	"moonshot-v1-32k":     32768,
	"moonshot-v1-8k":      8192,
	"kimi":                131072,
}

func LookupKnownWindow(name string) *int {
	name = strings.ReplaceAll(strings.ToLower(strip(name)), "_", "-")
	if i := strings.Index(name, ":"); i > 0 {
		name = name[:i]
	}
	keys := make([]string, 0, len(knownWindows))
	for k := range knownWindows {
		keys = append(keys, k)
	}
	sort.Slice(keys, func(i, j int) bool { return len(keys[i]) > len(keys[j]) })
	match := func(s, p string) bool { return s == p || strings.HasPrefix(s, p+"-") }
	for _, k := range keys {
		if match(name, k) {
			v := knownWindows[k]
			return &v
		}
	}
	for _, k := range keys {
		if len(k) < 5 {
			continue
		}
		for _, part := range strings.Split(name, "/") {
			if match(part, k) || strings.Contains(part, "-"+k+"-") || strings.HasSuffix(part, "-"+k) {
				v := knownWindows[k]
				return &v
			}
		}
	}
	return nil
}

type windowReference struct {
	Tokens int
	URL    string
	Input  *int
}

func findReference(name, base string) *windowReference {
	name = strings.ToLower(strip(name))
	name = name[strings.LastIndex(name, "/")+1:]
	name = strings.TrimPrefix(name, "cmaas-")
	u, _ := url.Parse(base)
	host := ""
	if u != nil {
		host = strings.ToLower(u.Hostname())
	}
	aliyun := host == "dashscope.aliyuncs.com" || strings.HasSuffix(host, ".dashscope.aliyuncs.com") || (strings.HasPrefix(host, "dashscope-") && strings.HasSuffix(host, ".aliyuncs.com"))
	if aliyun {
		switch name {
		case "qwen3.8-flash":
			v := 983616
			return &windowReference{1000000, "https://help.aliyun.com/zh/model-studio/qwen3-8-flash", &v}
		case "deepseek-v4.1-flash":
			return &windowReference{1000000, "https://help.aliyun.com/zh/model-studio/deepseek-v4-1-flash", nil}
		case "glm-5.3":
			return &windowReference{1048576, "https://help.aliyun.com/zh/model-studio/glm-5-3", nil}
		case "minimax-m2.5":
			return &windowReference{204800, "https://help.aliyun.com/zh/model-studio/minimax-m2-5", nil}
		}
	}
	valid := name == "deepseek-v4-pro"
	if tail, ok := strings.CutPrefix(name, "deepseek-v4-pro-"); ok && len(tail) >= 4 && len(tail) <= 8 {
		valid = true
		for _, r := range tail {
			if r < '0' || r > '9' {
				valid = false
			}
		}
	}
	if !valid {
		return nil
	}
	if aliyun {
		return &windowReference{1000000, "https://help.aliyun.com/zh/model-studio/deepseek-v4-pro", nil}
	}
	return &windowReference{1048576, "https://api-docs.deepseek.com/api/list-models/", nil}
}
