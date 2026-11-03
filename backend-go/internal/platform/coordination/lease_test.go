package coordination

import (
	"context"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/database"
)

func TestExplicitPoolAndJavaCapacityBoundaries(t *testing.T) {
	if _, err := New(nil); err == nil {
		t.Fatal("必须显式提供池")
	}
	for _, test := range []struct {
		resource string
		capacity int
	}{
		{"model", 0}, {"model", 101}, {strings.Repeat("a", 161), 1}, {strings.Repeat("😀", 81), 1}, {"\x00", 1}, {string([]byte{0xff}), 1},
	} {
		if validateAcquire(test.resource, test.capacity) == nil {
			t.Fatal("拒绝无效租约参数")
		}
	}
	if validateAcquire(strings.Repeat("😀", 80), 100) != nil || validateAcquire("", 1) != nil {
		t.Fatal("保留 Java UTF-16 长度和空资源语义")
	}
	var tx database.Transaction
	lease := &Lease{}
	if lease.Fence(context.Background(), nil) != ErrWriteTransactionRequired || lease.Fence(context.Background(), &tx) != ErrWriteTransactionRequired {
		t.Fatal("无活动写事务不得 fencing")
	}
}
