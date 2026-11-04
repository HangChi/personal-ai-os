package knowledge

import (
	"context"
	"database/sql"
	"sync"
	"sync/atomic"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
)

type Measurement struct {
	Count      uint64
	Total, Max time.Duration
	Buckets    []uint64
}
type maximumWindow struct {
	last    time.Time
	current int
	values  [3]time.Duration
}

func (w *maximumWindow) rotate(now time.Time) {
	if w.last.IsZero() {
		w.last = now
		return
	}
	steps := now.Sub(w.last) / time.Minute
	if steps <= 0 {
		return
	}
	w.last = w.last.Add(steps * time.Minute)
	if steps >= 3 {
		w.values = [3]time.Duration{}
		w.current = 0
		return
	}
	for ; steps > 0; steps-- {
		w.values[w.current] = 0
		w.current = (w.current + 1) % len(w.values)
	}
}

type Stats struct {
	mu       sync.Mutex
	Counters map[string]uint64
	Timers   map[string]Measurement
	Active   atomic.Int64
	maxima   map[string]*maximumWindow
	now      func() time.Time
}

func NewStats() *Stats {
	return &Stats{Counters: map[string]uint64{}, Timers: map[string]Measurement{}, maxima: map[string]*maximumWindow{}, now: time.Now}
}
func (s *Stats) Count(name, result string) {
	s.Add(name, result, 1)
}
func (s *Stats) Add(name, result string, n uint64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Counters[name+"|"+result] += n
}
func (s *Stats) Duration(name, result string, d time.Duration) {
	if d < 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	k := name + "|" + result
	v := s.Timers[k]
	v.Count++
	v.Total += d
	window := s.maxima[k]
	if window == nil {
		window = &maximumWindow{}
		s.maxima[k] = window
	}
	window.rotate(s.now())
	for i := range window.values {
		window.values[i] = max(window.values[i], d)
	}
	v.Max = window.values[window.current]
	if v.Buckets == nil {
		v.Buckets = make([]uint64, len(model.EmbeddingHistogramBounds()))
	}
	for i, bound := range model.EmbeddingHistogramBounds() {
		if d.Seconds() <= bound {
			v.Buckets[i]++
		}
	}
	s.Timers[k] = v
}
func (s *Stats) Snapshot() (map[string]uint64, map[string]Measurement) {
	s.mu.Lock()
	defer s.mu.Unlock()
	c := map[string]uint64{}
	t := map[string]Measurement{}
	for k, v := range s.Counters {
		c[k] = v
	}
	for k, v := range s.Timers {
		window := s.maxima[k]
		window.rotate(s.now())
		v.Max = window.values[window.current]
		v.Buckets = append([]uint64(nil), v.Buckets...)
		t[k] = v
	}
	return c, t
}

// MetricGauges uses the original SQL predicates in one bounded, read-only snapshot.
func (s *Store) MetricGauges(ctx context.Context) (map[string]float64, error) {
	var pending, oldest, reviewReady, reviewOldest, reviewFailed, searchPending float64
	err := s.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, `SELECT
		 (SELECT count(*) FROM knowledge_index_job WHERE status IN ('PENDING','RUNNING')),
		 (SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(available_at)),0) FROM knowledge_index_job WHERE status='PENDING' AND available_at<=now()),
		 (SELECT count(*) FROM knowledge_review_job WHERE next_attempt_at<=now()),
		 (SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(next_attempt_at)),0) FROM knowledge_review_job WHERE next_attempt_at<=now()),
		 (SELECT count(*) FROM knowledge_review_job WHERE next_attempt_at='infinity'::timestamptz),
		 (SELECT count(*) FROM knowledge_search_outbox)`).Scan(&pending, &oldest, &reviewReady, &reviewOldest, &reviewFailed, &searchPending)
	})
	return map[string]float64{"jobs.pending": pending, "jobs.oldest.ready.seconds": oldest,
		"review.ready": reviewReady, "review.oldest.ready.seconds": reviewOldest, "review.failed": reviewFailed, "search.pending": searchPending}, err
}
