//go:build integration

package calendar

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/testdb"
	"sync"
	"testing"
	"time"
)

func TestCalendarExchange(t *testing.T) {
	pool := testdb.Open(t)
	ctx := context.Background()
	zone, _ := time.LoadLocation("Asia/Shanghai")
	s := NewEvents(pool, config.CalendarProperties{}, zone)
	if e := pool.WithConn(ctx, func(c *sql.Conn) error { _, e := c.ExecContext(ctx, "TRUNCATE calendar_event CASCADE"); return e }); e != nil {
		t.Fatal(e)
	}
	text := icsFixture("")
	preview, e := s.PreviewICS(ctx, text)
	if e != nil {
		t.Fatal(e)
	}
	var count int
	check := func(n int) {
		t.Helper()
		e := pool.WithConn(ctx, func(c *sql.Conn) error {
			return c.QueryRowContext(ctx, "SELECT count(*) FROM calendar_event").Scan(&count)
		})
		if e != nil || count != n {
			t.Fatal(count, e)
		}
	}
	check(0)
	if _, e := s.ImportICS(ctx, text, "stale"); e == nil {
		t.Fatal("digest mismatch accepted")
	}
	check(0)
	var wg sync.WaitGroup
	results := make(chan ICSImported, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() { defer wg.Done(); v, e := s.ImportICS(ctx, text, preview.Digest); results <- v; errs <- e }()
	}
	wg.Wait()
	close(results)
	close(errs)
	created := 0
	for v := range results {
		created += v.Created
	}
	for e := range errs {
		if e != nil {
			t.Fatal(e)
		}
	}
	if created != 1 {
		t.Fatal(created)
	}
	check(1)
	preview, e = s.PreviewICS(ctx, text)
	if e != nil || preview.DuplicateCount != 1 {
		t.Fatal(preview, e)
	}
	definitions, e := s.definitions(ctx)
	if e != nil {
		t.Fatal(e)
	}
	exported, e := writeICS(definitions, zone)
	if e != nil {
		t.Fatal(e)
	}
	v, e := s.ImportICS(ctx, exported, icsDigest(exported))
	if e != nil || v.Skipped != 1 {
		t.Fatal(v, e)
	}
	// 半开区间以及从窗口之前延伸的长日程。
	if e = pool.WithConn(ctx, func(c *sql.Conn) error {
		_, e := c.ExecContext(ctx, `INSERT INTO calendar_event(title,starts_at,ends_at,recurrence_type) VALUES('long','2026-11-05T01:00Z','2026-11-06T01:30Z','NONE'),('adjacent','2026-11-06T02:00Z','2026-11-06T03:00Z','NONE')`)
		return e
	}); e != nil {
		t.Fatal(e)
	}
	from, _ := time.Parse(time.RFC3339, "2026-11-06T00:00:00Z")
	raw, e := s.conflicts(ctx, from, from.Add(24*time.Hour))
	if e != nil {
		t.Fatal(e)
	}
	if raw.(map[string]any)["total"] != 1 {
		t.Fatal(raw)
	}
}
