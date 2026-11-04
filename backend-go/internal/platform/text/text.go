// Package text preserves the shared Java Texts normalization and UTF-16 limits.
package text

import (
	"strconv"
	"strings"
	"unicode"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/api"
)

const TitleMaxLength = 200

func javaSpace(r rune) bool {
	return r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || r >= 0x1c && r <= 0x1f || r != 0xa0 && r != 0x2007 && r != 0x202f && (unicode.Is(unicode.Zs, r) || r == 0x2028 || r == 0x2029)
}

func TrimToNull(value *string) *string {
	if value == nil {
		return nil
	}
	stripped := strings.TrimFunc(*value, javaSpace)
	if stripped == "" {
		return nil
	}
	return &stripped
}

func RequireNotBlank(value *string, label string, maximum int) (string, error) {
	stripped := TrimToNull(value)
	if stripped == nil {
		return "", api.NewBusinessError(api.ValidationFailed, label+"不能为空")
	}
	if len(utf16.Encode([]rune(*stripped))) > maximum {
		return "", api.NewBusinessError(api.ValidationFailed, label+"长度不能超过 "+strconv.Itoa(maximum))
	}
	return *stripped, nil
}
