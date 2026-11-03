//go:build integration

package chat

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

func countFor(t *testing.T, pool *database.Pool, query string, args ...any) int {
	t.Helper()
	var n int
	if err := pool.WithConn(context.Background(), func(c *sql.Conn) error { return c.QueryRowContext(context.Background(), query, args...).Scan(&n) }); err != nil {
		t.Fatal(err)
	}
	return n
}

func awaitUsage(t *testing.T, pool *database.Pool, id string) UsageRecord {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for {
		var v UsageRecord
		err := pool.WithConn(context.Background(), func(c *sql.Conn) error {
			return c.QueryRowContext(context.Background(), `SELECT COALESCE(model,''),prompt_tokens,completion_tokens,source FROM token_usage WHERE conversation_id=$1 ORDER BY id DESC LIMIT 1`, id).Scan(&v.Model, &v.Prompt, &v.Completion, &v.Source)
		})
		if err == nil {
			return v
		}
		if !errors.Is(err, sql.ErrNoRows) || time.Now().After(deadline) {
			t.Fatal("usage did not persist", err)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestPostgresP4UsageTimezoneSourcesBoundsAndLatestZero(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	zone, err := time.LoadLocation("America/New_York")
	if err != nil {
		t.Fatal(err)
	}
	u := &UsageService{Pool: pool, Zone: zone, Now: func() time.Time { return time.Date(2026, 3, 8, 12, 0, 0, 0, zone) }}
	// DST 开始日为23小时；用固定的合成日期并清理，避免与其他测试的当前日统计混合。
	id := "p4-usage-timezone"
	defer execute(t, pool, `DELETE FROM token_usage WHERE conversation_id=$1`, id)
	execute(t, pool, `DELETE FROM token_usage WHERE conversation_id=$1`, id)
	cases := []struct {
		at     string
		p, c   int
		source string
	}{
		{"2026-03-07T04:59:59Z", 999, 999, "ACTUAL"},
		{"2026-03-07T05:00:00Z", 10, 2, "ESTIMATED"},
		{"2026-03-08T04:59:59Z", 20, 3, "ACTUAL"},
		{"2026-03-08T05:00:00Z", 5, 1, "ESTIMATED"},
		{"2026-03-09T03:59:59Z", 0, 8, "ACTUAL"},
		{"2026-03-09T04:00:00Z", 999, 999, "ACTUAL"},
	}
	for _, c := range cases {
		at, _ := time.Parse(time.RFC3339, c.at)
		if err = u.Persist(ctx, id, UsageRecord{Prompt: c.p, Completion: c.c, Source: c.source}, at); err != nil {
			t.Fatal(err)
		}
	}
	days := 2
	got, err := u.Stats(ctx, &days)
	if err != nil || len(got.Daily) != 2 || got.Turns != 4 || got.Prompt != 35 || got.Completion != 14 || got.Actual != 31 || got.Estimated != 18 || !got.HasData {
		t.Fatal(got, err)
	}
	if got.Daily[0].Date != "2026-03-07" || got.Daily[0].Estimated || got.Daily[1].Date != "2026-03-08" || got.Daily[1].Estimated {
		t.Fatal(got.Daily)
	}
	// 同时刻按 id 取最近 ACTUAL，0 prompt 不等于“没有”；新估算不能覆盖真实值。
	execute(t, pool, `DELETE FROM token_usage WHERE conversation_id=$1 AND created_at >= '2026-03-09T04:00:00Z'`, id)
	n, err := u.Latest(ctx, id)
	if err != nil || n == nil || *n != 0 {
		t.Fatal(n, err)
	}
	if err = u.Persist(ctx, id, UsageRecord{Prompt: 55, Source: "ESTIMATED"}, u.Now()); err != nil {
		t.Fatal(err)
	}
	n, err = u.Latest(ctx, id)
	if err != nil || n == nil || *n != 0 {
		t.Fatal(n, err)
	}
	for _, input := range []int{-1, 0, 1, 366, 2147483647} {
		v, err := u.Stats(ctx, &input)
		if err != nil || v.Days != min(365, max(1, input)) {
			t.Fatal(v, err)
		}
	}
	if v, err := u.Stats(ctx, nil); err != nil || v.Days != 30 {
		t.Fatal(v, err)
	}
	before := countFor(t, pool, `SELECT count(*) FROM token_usage WHERE conversation_id=$1`, id)
	if err = u.Persist(ctx, id, UsageRecord{Source: "ACTUAL"}, u.Now()); err != nil {
		t.Fatal(err)
	}
	if err = u.Persist(ctx, id, UsageRecord{Prompt: 1, Source: "INVALID"}, u.Now()); err == nil {
		t.Fatal("invalid source")
	}
	if after := countFor(t, pool, `SELECT count(*) FROM token_usage WHERE conversation_id=$1`, id); after != before {
		t.Fatal(before, after)
	}
}
