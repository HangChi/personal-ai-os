package text

import (
	"errors"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
)

func value(s string) *string { return &s }

func TestJavaTextsNullStripRequiredAndUTF16Boundaries(t *testing.T) {
	for _, v := range []*string{nil, value(""), value("  "), value("\t\n\u001f\u3000")} {
		if TrimToNull(v) != nil {
			t.Fatal("blank not normalized to nil")
		}
		_, err := RequireNotBlank(v, "标题", TitleMaxLength)
		var problem *api.BusinessError
		if !errors.As(err, &problem) || problem.Code() != api.ValidationFailed || problem.Error() != "标题不能为空" {
			t.Fatal("blank error contract", err)
		}
	}
	for _, v := range []struct{ raw, want string }{{" 交周报 ", "交周报"}, {"交 周 报", "交 周 报"}, {"\u00a0x\u00a0", "\u00a0x\u00a0"}, {"\u2007x\u202f", "\u2007x\u202f"}} {
		got := TrimToNull(&v.raw)
		if got == nil || *got != v.want {
			t.Fatal("strip contract")
		}
		if got, err := RequireNotBlank(&v.raw, "标题", 200); err != nil || got != v.want {
			t.Fatal("required strip", err)
		}
	}
	for _, v := range []string{strings.Repeat("a", 200), strings.Repeat("😀", 100)} {
		if got, err := RequireNotBlank(value("  "+v+"  "), "标题", 200); err != nil || got != v {
			t.Fatal("limit must use stripped UTF-16 length", err)
		}
	}
	for _, v := range []string{strings.Repeat("a", 201), strings.Repeat("😀", 101)} {
		_, err := RequireNotBlank(&v, "标题", 200)
		var problem *api.BusinessError
		if !errors.As(err, &problem) || problem.Code() != api.ValidationFailed || problem.Error() != "标题长度不能超过 200" {
			t.Fatal("overlong error contract", err)
		}
	}
}
