package knowledge

import (
	"sync"
	"testing"
	"time"
)

func TestP8KnowledgeTimerWindowsBucketsAndConcurrentSnapshots(t *testing.T) {
	stats := NewStats()
	now := time.Unix(1700000000, 0)
	stats.now = func() time.Time { return now }
	stats.Duration("jobs.duration", "", -time.Second)
	stats.Duration("jobs.duration", "", 50*time.Millisecond)
	_, timers := stats.Snapshot()
	first := timers["jobs.duration|"]
	if first.Count != 1 || first.Total != 50*time.Millisecond || first.Max != first.Total || first.Buckets[len(first.Buckets)-1] != 1 {
		t.Fatal(first)
	}
	first.Buckets[len(first.Buckets)-1] = 900
	now = now.Add(3 * time.Minute)
	_, timers = stats.Snapshot()
	expired := timers["jobs.duration|"]
	if expired.Max != 0 || expired.Total != first.Total || expired.Count != 1 || expired.Buckets[len(expired.Buckets)-1] != 1 {
		t.Fatal(expired)
	}
	var workers sync.WaitGroup
	for i := 0; i < 10; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			for n := 0; n < 50; n++ {
				stats.Duration("rag.stage.recall", "OK", time.Millisecond)
				stats.Count("rag.results", "READY")
				stats.Snapshot()
			}
		}()
	}
	workers.Wait()
	counts, timers := stats.Snapshot()
	if counts["rag.results|READY"] != 500 || timers["rag.stage.recall|OK"].Count != 500 {
		t.Fatal(counts, timers)
	}
}
