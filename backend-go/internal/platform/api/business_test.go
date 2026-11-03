package api

import (
	"errors"
	"fmt"
	"testing"
)

func TestBusinessErrorsKeepExplicitPublicMessageAndRejectInvalidCodes(t *testing.T) {
	for _, message := range []string{"", "指定任务不存在😀"} {
		original := NewBusinessError(ResourceNotFound, message)
		var problem *BusinessError
		if !errors.As(fmt.Errorf("service: %w", original), &problem) || problem.Code() != ResourceNotFound || problem.Error() != message {
			t.Fatal("业务错误必须保留显式文案和errors.As", problem)
		}
	}
	for _, code := range []ErrorCode{Success, -1, 99999} {
		problem := NewBusinessError(code, "invalid-public-code")
		if problem.Code() != InternalError || problem.Error() != InternalError.Message() {
			t.Fatal("非法业务码必须失败关闭", problem)
		}
	}
}
