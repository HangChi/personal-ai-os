package knowledge

import (
	"sync"
	"sync/atomic"
	"time"
)

type Measurement struct {
	Count      uint64
	Total, Max time.Duration
}
type Stats struct {
	mu       sync.Mutex
	Counters map[string]uint64
	Timers   map[string]Measurement
	Active   atomic.Int64
}

func NewStats() *Stats {
	return &Stats{Counters: map[string]uint64{}, Timers: map[string]Measurement{}}
}
func (s *Stats) Count(name, result string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Counters[name+"|"+result]++
}
func (s *Stats) Duration(name, result string, d time.Duration) {
	s.mu.Lock()
	defer s.mu.Unlock()
	k := name + "|" + result
	v := s.Timers[k]
	v.Count++
	v.Total += d
	v.Max = max(v.Max, d)
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
		t[k] = v
	}
	return c, t
}
