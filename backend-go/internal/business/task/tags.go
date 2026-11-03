package task

import (
	"errors"
	"strings"
	"unicode"
	"unicode/utf16"
)

func NormalizeTags(raw []string) ([]string, error) {
	out := make([]string, 0)
	seen := make(map[string]bool)
	for _, item := range raw {
		tag := strings.TrimFunc(item, unicode.IsSpace)
		if tag == "" {
			continue
		}
		if len(utf16.Encode([]rune(tag))) > 20 {
			return nil, errors.New("单个标签不能超过20个字符")
		}
		if !seen[tag] {
			seen[tag] = true
			out = append(out, tag)
		}
	}
	if len(out) > 5 {
		return nil, errors.New("最多5个标签")
	}
	return out, nil
}
