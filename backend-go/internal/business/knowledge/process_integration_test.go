//go:build integration

package knowledge

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

// 子进程复用真实索引路径；强杀不会执行 Go 的 defer 或释放数据库租约。
func TestP6IndexWorkerProcess(t *testing.T) {
	mode := os.Getenv("GO_TEST_P6_WORKER")
	if mode == "" {
		t.Skip("仅由崩溃恢复测试启动")
	}
	pool := fixturePool(t)
	locks, e := coordination.New(pool)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = locks.Close(context.Background()) })
	embedding := embeddingFunc(fixtureEmbedding)
	if mode == "crash" {
		embedding = func(ctx context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
			fmt.Println("P6_WORKER_EMBEDDING")
			<-ctx.Done()
			return model.EmbeddingResponse{}, ctx.Err()
		}
	}
	s, e := NewStore(context.Background(), pool, Reader{Properties: testProperties(t)}, embedding, locks, "public", "vector_store")
	if e != nil {
		t.Fatal(e)
	}
	if ran, e := (&Jobs{Store: s}).RunNext(context.Background()); !ran || e != nil {
		t.Fatal(ran, e)
	}
}

func TestP6IndexSIGKILLAndProcessRestart(t *testing.T) {
	s := fixtureStore(t)
	j := &Jobs{Store: s}
	ctx := context.Background()
	job, e := j.Submit(ctx, newID(), "", "crash.txt", nil, []byte(strings.Repeat("崩溃后从数据库重新领取完整任务。", 20)))
	if e != nil {
		t.Fatal(e)
	}
	bin, e := os.Executable()
	if e != nil {
		t.Fatal(e)
	}
	bounded, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	child := exec.CommandContext(bounded, bin, "-test.run=^TestP6IndexWorkerProcess$", "-test.v")
	child.Env = append(os.Environ(), "GO_TEST_P6_WORKER=crash")
	stdout, e := child.StdoutPipe()
	if e != nil {
		t.Fatal(e)
	}
	if e = child.Start(); e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _ = child.Process.Kill() })
	ready := make(chan struct{})
	go func() {
		scanner := bufio.NewScanner(stdout)
		for scanner.Scan() {
			if scanner.Text() == "P6_WORKER_EMBEDDING" {
				close(ready)
				return
			}
		}
	}()
	select {
	case <-ready:
	case <-bounded.Done():
		_ = child.Process.Kill()
		_ = child.Wait()
		t.Fatal("worker did not reach embedding")
	}
	if c, e := j.Claim(ctx); e != nil || c != nil {
		_ = child.Process.Kill()
		_ = child.Wait()
		t.Fatal("live process lost exclusive ownership", c, e)
	}
	if e = child.Process.Kill(); e != nil {
		t.Fatal(e)
	}
	if e = child.Wait(); e == nil {
		t.Fatal("worker was not killed")
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_document") != 0 || !scalar[bool](t, s.Pool, "SELECT payload IS NOT NULL AND status='RUNNING' FROM knowledge_index_job WHERE id=$1", job.ID) {
		t.Fatal("crash left partial writes or lost its durable payload")
	}
	// 前移数据库时钟边界，避免等待完整的 60/120 秒租期；仍由新进程实际抢占。
	execSQL(t, s.Pool, "UPDATE knowledge_index_job SET lease_until=now()-interval '1 second' WHERE id=$1", job.ID)
	execSQL(t, s.Pool, "UPDATE runtime_lease SET lease_until=clock_timestamp()-interval '1 second' WHERE resource=$1", "document:"+job.DocumentID)
	restart := exec.CommandContext(bounded, bin, "-test.run=^TestP6IndexWorkerProcess$", "-test.v")
	restart.Env = append(os.Environ(), "GO_TEST_P6_WORKER=recover")
	if output, e := restart.CombinedOutput(); e != nil {
		t.Fatalf("restart failed: %v\n%s", e, output)
	}
	current, e := j.Get(ctx, job.ID)
	if e != nil || current.Status != "SUCCEEDED" || current.Attempts != 2 || current.Result == nil {
		t.Fatal(current, e)
	}
	document, e := s.Document(ctx, job.DocumentID)
	if e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_document") != 1 || scalar[int](t, s.Pool, "SELECT count(*) FROM vector_store") != document.ChunkCount {
		t.Fatal("restart duplicated or lost index writes")
	}
}
