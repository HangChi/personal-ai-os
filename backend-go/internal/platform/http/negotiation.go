package httpmiddleware

import (
	"math"
	"mime"
	"sort"
	"strconv"
	"strings"

	"personal-ai-os/backend-go/internal/platform/api"
)

type jsonMediaCandidate struct {
	media      string
	parameters map[string]string
	quality    float64
}

// SelectJSONMediaType保留Spring的质量优先/相同质量具体类型优先及稳定声明顺序。
// 原框架不排除q=0；整条Accept中的畸形项不能因另有合法项被静默忽略。
func SelectJSONMediaType(accept string, supported []string) (string, bool) {
	if len(supported) == 0 {
		return "", false
	}
	parts, valid := splitAccept(accept)
	if !valid {
		return "", false
	}
	if len(parts) == 0 {
		return supported[0], true
	}
	mvc := false
	for _, media := range supported {
		mvc = mvc || media == "application/*+json"
	}
	candidates := []jsonMediaCandidate{}
	for _, part := range parts {
		if part == "*" {
			part = "*/*"
		}
		media, params, err := mime.ParseMediaType(part)
		if err != nil || !strings.Contains(media, "/") {
			return "", false
		}
		quality := 1.0
		if q, found := params["q"]; found {
			quality, err = strconv.ParseFloat(q, 64)
			if err != nil || math.IsNaN(quality) || quality < 0 || quality > 1 {
				return "", false
			}
		}
		if charset := params["charset"]; charset != "" {
			if _, err := api.CanonicalCharset(charset); err != nil {
				return "", false
			}
			if mvc {
				if _, err := api.JSONEncoding(charset); err != nil {
					continue
				}
			}
		}
		for _, produced := range supported {
			compatible := media == "*/*" || media == "application/*" || media == produced ||
				media == "application/*+json" && (produced == "application/json" || strings.HasSuffix(produced, "+json")) ||
				produced == "application/*+json" && strings.HasPrefix(media, "application/") && strings.HasSuffix(media, "+json")
			if !compatible {
				continue
			}
			candidate := jsonMediaCandidate{media: media, parameters: params, quality: quality}
			if strings.Contains(media, "*") {
				candidate.media, candidate.parameters = produced, nil
			}
			if strings.Contains(candidate.media, "*") {
				continue
			}
			candidates = append(candidates, candidate)
		}
	}
	if len(candidates) == 0 {
		return "", false
	}
	sort.SliceStable(candidates, func(i, j int) bool {
		a, b := candidates[i], candidates[j]
		if a.quality != b.quality {
			return a.quality > b.quality
		}
		return a.media == b.media && len(a.parameters) > len(b.parameters)
	})
	selected := candidates[0]
	keys := make([]string, 0, len(selected.parameters))
	for key := range selected.parameters {
		if key != "q" {
			keys = append(keys, key)
		}
	}
	sort.Strings(keys)
	result := selected.media
	for _, key := range keys {
		formatted := mime.FormatMediaType(selected.media, map[string]string{key: selected.parameters[key]})
		result += ";" + strings.TrimPrefix(formatted, selected.media+"; ")
	}
	return result, true
}

func splitAccept(value string) ([]string, bool) {
	parts := []string{}
	start, quoted, escaped := 0, false, false
	for i, c := range value {
		if escaped {
			escaped = false
			continue
		}
		if quoted && c == '\\' {
			escaped = true
		} else if c == '"' {
			quoted = !quoted
		} else if !quoted && c == ',' {
			if part := strings.TrimSpace(value[start:i]); part != "" {
				parts = append(parts, part)
			}
			start = i + 1
		}
	}
	if quoted || escaped {
		return nil, false
	}
	if part := strings.TrimSpace(value[start:]); part != "" {
		parts = append(parts, part)
	}
	return parts, true
}
