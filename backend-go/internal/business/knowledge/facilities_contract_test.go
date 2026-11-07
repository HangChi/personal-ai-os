package knowledge

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type contractRedis func(context.Context, string, []string, ...any) (any, error)

func (f contractRedis) Eval(c context.Context, q string, k []string, a ...any) (any, error) {
	return f(c, q, k, a...)
}
func TestCandidateCacheGenerationFencingErrorsAndBypass(t *testing.T) {
	for _, mode := range []string{"hit", "miss", "stale-hit", "changed-before-write", "generation-error", "read-error", "corrupt", "oversized", "write-error", "load-error", "transaction", "closed"} {
		t.Run(mode, func(t *testing.T) {
			generation := queryStep("SELECT generation::text", sqlValues("7"))
			if mode == "generation-error" {
				generation.err = errors.New("database offline")
			}
			steps := []sqlStep{}
			if mode != "transaction" && mode != "closed" {
				steps = append(steps, generation)
			}
			if mode == "hit" || mode == "stale-hit" {
				v := "7"
				if mode == "stale-hit" {
					v = "8"
				}
				steps = append(steps, queryStep("SELECT generation::text", sqlValues(v)))
			}
			writes := mode == "miss" || mode == "stale-hit" || mode == "changed-before-write" || mode == "oversized" || mode == "write-error"
			if writes {
				v := "7"
				if mode == "changed-before-write" {
					v = "8"
				}
				steps = append(steps, queryStep("SELECT generation::text", sqlValues(v)))
			}
			s := contractStore(t, steps...)
			gets, sets, loads := 0, 0, 0
			var readKey string
			redis := contractRedis(func(ctx context.Context, script string, keys []string, args ...any) (any, error) {
				if _, ok := ctx.Deadline(); !ok {
					t.Fatal("unbounded cache access")
				}
				if len(keys) != 1 || strings.Contains(keys[0], "secret") {
					t.Fatal(keys)
				}
				if strings.Contains(script, "GET") {
					gets++
					readKey = keys[0]
					switch mode {
					case "hit", "stale-hit":
						b, _ := json.Marshal([]Hit{{Content: "cached"}})
						return string(b), nil
					case "corrupt":
						return "invalid json", nil
					case "read-error":
						return nil, errors.New("redis offline")
					case "oversized":
						return strings.Repeat("x", 256*1024+1), nil
					}
					return nil, nil
				}
				sets++
				if keys[0] != readKey || !strings.Contains(script, "SETEX") || !strings.Contains(args[0].(string), "loaded") {
					t.Fatal(keys, args)
				}
				if mode == "write-error" {
					return nil, errors.New("write failed")
				}
				return "OK", nil
			})
			c, e := NewCandidateCache(s, redis, "secret-namespace", "secret-config")
			if e != nil {
				t.Fatal(e)
			}
			if mode == "closed" {
				c.Close()
				c.Close()
			}
			ready := func() bool { return true }
			failure := errors.New("recall failed")
			hits, e := c.Get(context.Background(), "secret query", 4, mode == "transaction", ready, func(ready bool) ([]Hit, error) {
				loads++
				if !ready {
					t.Fatal("readiness changed")
				}
				if mode == "load-error" {
					return nil, failure
				}
				return []Hit{{Content: "loaded"}}, nil
			})
			if mode == "load-error" {
				if !errors.Is(e, failure) || hits != nil {
					t.Fatal(hits, e)
				}
				return
			}
			if e != nil || len(hits) != 1 {
				t.Fatal(hits, e)
			}
			expectedLoads := 1
			if mode == "hit" {
				expectedLoads = 0
				if hits[0].Content != "cached" {
					t.Fatal(hits)
				}
			}
			if loads != expectedLoads {
				t.Fatal(loads)
			}
			expectedGets := 1
			if mode == "transaction" || mode == "closed" || mode == "generation-error" {
				expectedGets = 0
			}
			expectedSets := 0
			if writes && mode != "changed-before-write" {
				expectedSets = 1
			}
			if gets != expectedGets || sets != expectedSets {
				t.Fatal(gets, sets)
			}
			counts, _ := c.Stats.Snapshot()
			if mode == "hit" && counts["candidate.cache|hit"] != 1 {
				t.Fatal(counts)
			}
			if mode == "write-error" && counts["candidate.cache|write_error"] != 1 {
				t.Fatal(counts)
			}
		})
	}
	if _, e := NewCandidateCache(nil, nil, "", ""); e == nil {
		t.Fatal("missing cache dependencies")
	}
}

type contractFetch func(context.Context, string) (WebPage, error)

func (f contractFetch) Fetch(c context.Context, u string) (WebPage, error) { return f(c, u) }
func captureRow(pending any) []driver.Value {
	return sqlValues(contractDocA, "https://fixture.invalid/article", contractDocB, 2, pending, "SUCCEEDED", nil, time.Date(2026, 11, 7, 9, 0, 0, 0, time.UTC))
}
func TestCapturesSubmitRefreshHistoryAndSafeFailures(t *testing.T) {
	ctx := context.Background()
	t.Run("submit-list-versions", func(t *testing.T) {
		queue := queryStep("SELECT queue_knowledge_capture", sqlValues(contractDocA))
		queue.check = func(a []driver.NamedValue) {
			if a[0].Value != "https://fixture.invalid/article" || a[3].Value != ContentHash("正文") {
				t.Fatal(a)
			}
		}
		s := contractStore(t, terminalStep("begin"), queue, terminalStep("commit"), queryStep("WHERE c.id=$1", captureRow(nil)), queryStep("SELECT count(*) FROM knowledge_capture", sqlValues(1)), queryStep("ORDER BY c.created_at", captureRow(nil)), queryStep("WHERE c.id=$1", captureRow(nil)), queryStep("SELECT count(*) FROM knowledge_capture_version", sqlValues(2)), queryStep("SELECT version,title", sqlValues(2, "新标题", "正文", "hash", "SUCCEEDED", time.Date(2026, 11, 7, 9, 0, 0, 0, time.UTC))))
		fetched := 0
		c := &Captures{Pool: s.Pool, Fetcher: contractFetch(func(_ context.Context, url string) (WebPage, error) {
			fetched++
			return WebPage{URL: url, Title: "标题", Content: "正文"}, nil
		})}
		got, e := c.Capture(ctx, "https://fixture.invalid/article")
		if e != nil || got.ID != contractDocA || got.CurrentVersion != 2 || fetched != 1 {
			t.Fatal(got, e)
		}
		v, e := c.List(ctx)
		if e != nil || v.(map[string]any)["total"] != 1 {
			t.Fatal(v, e)
		}
		v, e = c.Versions(ctx, contractDocA)
		if e != nil || v.(map[string]any)["total"] != 2 || v.(map[string]any)["items"].([]CaptureVersion)[0].CapturedAt != "2026-11-07T09:00:00Z" {
			t.Fatal(v, e)
		}
	})
	t.Run("pending-refresh-does-not-fetch", func(t *testing.T) {
		s := contractStore(t, queryStep("WHERE c.id=$1", captureRow(contractChunkA)))
		c := &Captures{Pool: s.Pool, Fetcher: contractFetch(func(context.Context, string) (WebPage, error) {
			t.Fatal("requeued pending capture")
			return WebPage{}, nil
		})}
		v, e := c.Refresh(ctx, contractDocA)
		if e != nil || v.PendingJobID == nil {
			t.Fatal(v, e)
		}
	})
	t.Run("refresh-redacts-upstream", func(t *testing.T) {
		save := execStep("UPDATE knowledge_capture SET last_checked_at")
		save.check = func(a []driver.NamedValue) {
			if strings.Contains(a[0].Value.(string), "private") {
				t.Fatal(a)
			}
		}
		s := contractStore(t, queryStep("WHERE c.id=$1", captureRow(nil)), save)
		failure := errors.New("private upstream")
		c := &Captures{Pool: s.Pool, Fetcher: contractFetch(func(context.Context, string) (WebPage, error) { return WebPage{}, failure })}
		if _, e := c.Refresh(ctx, contractDocA); !errors.Is(e, failure) {
			t.Fatal(e)
		}
	})
	t.Run("queue-failure-rolls-back", func(t *testing.T) {
		step := queryStep("SELECT queue_knowledge_capture")
		step.err = errors.New("private SQL")
		s := contractStore(t, terminalStep("begin"), step, terminalStep("rollback"))
		c := &Captures{Pool: s.Pool, Fetcher: contractFetch(func(context.Context, string) (WebPage, error) { return WebPage{URL: "synthetic", Content: "text"}, nil })}
		_, e := c.Capture(ctx, "synthetic")
		expectBusiness(t, e, api.BusinessConflict, "采集提交失败")
		if strings.Contains(e.Error(), "private") {
			t.Fatal(e)
		}
	})
	t.Run("missing", func(t *testing.T) {
		s := contractStore(t, queryStep("WHERE c.id=$1"))
		_, e := (&Captures{Pool: s.Pool}).Get(ctx, contractDocA)
		expectBusiness(t, e, api.ResourceNotFound, "采集来源")
	})
}

type contractProject func(context.Context, string, int64, *Chunk) error

func (f contractProject) Project(c context.Context, id string, r int64, ch *Chunk) error {
	return f(c, id, r, ch)
}
func TestSearchProjectionFencesRevisionAndRetriesWithoutDeletingOutbox(t *testing.T) {
	for _, mode := range []string{"update", "delete", "project-error", "hydrate-error", "finish-error", "empty"} {
		t.Run(mode, func(t *testing.T) {
			claim := queryStep("WITH ready", sqlValues(contractChunkA, 9, 10))
			if mode == "empty" {
				claim.rows = nil
			}
			steps := []sqlStep{claim}
			var token any
			claim.check = func(a []driver.NamedValue) { token = a[0].Value }
			steps[0] = claim
			if mode != "empty" {
				hydrate := queryStep("JOIN knowledge_document", sqlValues(contractChunkA, "content", contractMetadata(contractDocA, 0), "[1,0]"))
				if mode == "delete" {
					hydrate.rows = nil
				}
				if mode == "hydrate-error" {
					hydrate.err = errors.New("hydrate failed")
				}
				finish := execStep("DELETE FROM knowledge_search_outbox")
				if mode == "project-error" || mode == "hydrate-error" {
					finish = execStep("UPDATE knowledge_search_outbox SET claim_token=NULL")
					finish.check = func(a []driver.NamedValue) {
						if a[0].Value != int64(300) || a[2].Value != int64(9) || a[3].Value != token {
							t.Fatal(a)
						}
					}
				} else {
					finish.check = func(a []driver.NamedValue) {
						if a[1].Value != int64(9) || a[2].Value != token {
							t.Fatal(a)
						}
					}
				}
				if mode == "finish-error" {
					finish.err = errors.New("finish failed")
				}
				steps = append(steps, hydrate, finish)
			}
			calls := 0
			p := &Projection{Store: contractStore(t, steps...), Stats: NewStats(), Gateway: contractProject(func(_ context.Context, id string, revision int64, ch *Chunk) error {
				calls++
				if id != contractChunkA || revision != 9 || (ch == nil) != (mode == "delete") {
					t.Fatal(id, revision, ch)
				}
				if mode == "project-error" {
					return errors.New("search offline")
				}
				return nil
			})}
			worked, e := p.RunNext(context.Background())
			if worked != (mode != "empty") || (e != nil) != (mode == "finish-error") {
				t.Fatal(worked, e)
			}
			expected := 1
			if mode == "hydrate-error" || mode == "empty" {
				expected = 0
			}
			if calls != expected {
				t.Fatal(calls)
			}
		})
	}
	s := contractStore(t, queryStep("SELECT count(*) FROM knowledge_search_outbox", sqlValues(3)), execStep("INSERT INTO knowledge_search_outbox"), queryStep("WITH ready"))
	p := &Projection{Store: s}
	if n, e := p.Pending(context.Background()); e != nil || n != 3 {
		t.Fatal(n, e)
	}
	if n, e := p.Reindex(context.Background()); e != nil || n != 1 {
		t.Fatal(n, e)
	}
	if e := p.Sync(context.Background()); e != nil {
		t.Fatal(e)
	}
}
