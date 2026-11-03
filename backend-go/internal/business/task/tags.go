package task

import (
	"unicode/utf16"
)

func NormalizeTags(raw []string) ([]string, error) {
	out := make([]string, 0)
	seen := make(map[string]bool)
	for _, item := range raw {
		tag := strip(item)
		if tag == "" {
			continue
		}
		if len(utf16.Encode([]rune(tag))) > 20 {
			return nil, invalid("单个标签不能超过 20 个字符")
		}
		if !seen[tag] {
			seen[tag] = true
			out = append(out, tag)
		}
	}
	if len(out) > 5 {
		return nil, invalid("最多 5 个标签")
	}
	return out, nil
}
