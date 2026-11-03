package modelgate

import (
	"context"
	"sync"
	"testing"
)

func TestLocalBoundAndIdempotentRelease(t *testing.T) {
	gate, err := NewLocal(3)
	if err != nil {
		t.Fatal(err)
	}
	var group sync.WaitGroup
	leases := make(chan *Lease, 1000)
	for range 1000 {
		group.Go(func() {
			lease, err := gate.TryAcquire(context.Background())
			if err != nil {
				t.Error(err)
			}
			if lease != nil {
				leases <- lease
			}
		})
	}
	group.Wait()
	close(leases)
	if len(leases) != 3 {
		t.Fatalf("1000 路竞争只能发出 3 个许可: %d", len(leases))
	}
	for lease := range leases {
		if err := lease.Release(context.Background()); err != nil {
			t.Fatal(err)
		}
		if err := lease.Release(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	for range 3 {
		lease, err := gate.TryAcquire(context.Background())
		if lease == nil || err != nil {
			t.Fatal("双重释放不能增加或丢失容量")
		}
	}
	if lease, err := gate.TryAcquire(context.Background()); lease != nil || err != nil {
		t.Fatal("不能额外发出许可")
	}
}

func TestInvalidLimitAndCancellation(t *testing.T) {
	for _, limit := range []int{0, -1, 1 << 31} {
		if _, err := NewLocal(limit); err == nil {
			t.Fatal("限制必须为 Java 正整数")
		}
	}
	gate, _ := NewLocal(1)
	if _, err := gate.TryAcquire(nil); err == nil {
		t.Fatal("拒绝 nil context")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if lease, err := gate.TryAcquire(ctx); lease != nil || err == nil {
		t.Fatal("取消不能取得许可")
	}
	lease, err := gate.TryAcquire(context.Background())
	if lease == nil || err != nil {
		t.Fatal("取消不得占用本地许可")
	}
	_ = lease.Release(context.Background())
}
