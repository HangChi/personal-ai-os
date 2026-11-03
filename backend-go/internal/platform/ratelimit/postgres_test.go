package ratelimit

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

func TestPostgresBucketRequiresExplicitPool(t *testing.T) {
	if _, err := NewPostgresBucket(nil); err == nil {
		t.Fatal("不能隐式连接数据库或退化成本地桶")
	}
}

func TestPostgresBucketRejectsInvalidParametersBeforeDatabase(t *testing.T) {
	// 空 Pool 未连接任何驱动；非法参数不能触及其内部连接。
	bucket, err := NewPostgresBucket(&database.Pool{})
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		capacity int
		period   time.Duration
	}{
		{0, time.Minute}, {-1, time.Minute}, {1, 0}, {1, -1}, {1 << 31, time.Minute},
	} {
		if allowed, err := bucket.Consume(context.Background(), "model-rate", test.capacity, test.period); allowed || err == nil {
			t.Fatalf("非法参数必须报错且拒绝准入：%+v", test)
		}
	}
	if allowed, err := bucket.Consume(nil, "model-rate", 1, time.Minute); allowed || err == nil {
		t.Fatal("nil context 不能消费令牌")
	}
}

func TestPostgresBucketErrorRedactsAndPreservesCause(t *testing.T) {
	cause := errors.New("private SQL / endpoint / credentials")
	err := &postgresBucketError{cause: cause}
	if !errors.Is(err, cause) {
		t.Fatal("应可由内部调用方识别原始原因")
	}
	for _, formatted := range []string{fmt.Sprint(err), fmt.Sprintf("%+v", err), fmt.Sprintf("%#v", err)} {
		if strings.Contains(formatted, "private") || formatted != "数据库共享限流消费失败" {
			t.Fatal("数据库错误格式化不能暴露原因")
		}
	}
}
