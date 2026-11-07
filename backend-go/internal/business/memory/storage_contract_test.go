package memory

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"math"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/model"
	"strings"
	"testing"
	"time"
)

const memoryID = "10000000-0000-4000-8000-000000000001"

func memoryRow() []driver.Value {
	now := time.Now().UTC()
	return sqlValues(memoryID, "记忆内容", "MANUAL", true, nil, now, now, 2, now)
}
func TestMemoryStorageLifecycleContract(t *testing.T) {
	ctx := context.Background()
	steps := []sqlStep{queryStep("bool_and", sqlValues(true)), queryStep("ORDER BY pinned", memoryRow()), queryStep("content_hash=$1"), terminalStep("begin"), queryStep("INSERT INTO memory", memoryRow()), terminalStep("commit"), terminalStep("begin"), queryStep("UPDATE memory SET content", memoryRow()), terminalStep("commit"), queryStep("SET pinned", memoryRow()), queryStep("DELETE FROM memory", memoryRow())}
	s, e := NewStore(ctx, contractPool(t, steps...))
	if e != nil {
		t.Fatal(e)
	}
	list, e := s.List(ctx)
	if e != nil || len(list) != 1 || list[0].HitCount != 2 {
		t.Fatal(list, e)
	}
	a, e := s.Create(ctx, " 记忆内容 ")
	if e != nil || a.ID != memoryID {
		t.Fatal(a, e)
	}
	if _, e = s.Update(ctx, memoryID, "更新"); e != nil {
		t.Fatal(e)
	}
	if _, e = s.Pin(ctx, memoryID, false); e != nil {
		t.Fatal(e)
	}
	if e = s.Delete(ctx, memoryID); e != nil {
		t.Fatal(e)
	}
}
func TestMemoryDuplicateFallbackAndMissingContract(t *testing.T) {
	for _, mode := range []string{"duplicate", "racing", "updateMissing", "pinMissing", "deleteMissing"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{}
			switch mode {
			case "duplicate":
				steps = append(steps, queryStep("content_hash", memoryRow()))
			case "racing":
				steps = append(steps, queryStep("content_hash"), terminalStep("begin"), queryStep("INSERT INTO memory"), queryStep("content_hash", memoryRow()), terminalStep("commit"))
			case "updateMissing":
				steps = append(steps, terminalStep("begin"), queryStep("UPDATE memory"), terminalStep("rollback"))
			case "pinMissing":
				steps = append(steps, queryStep("UPDATE memory"))
			default:
				steps = append(steps, queryStep("DELETE FROM memory"))
			}
			s := &Store{pool: contractPool(t, steps...)}
			var e error
			switch mode {
			case "duplicate", "racing":
				var a Entry
				a, e = s.CreateAuto(context.Background(), "记忆内容")
				if e == nil && a.ID != memoryID {
					t.Fatal(a)
				}
			case "updateMissing":
				_, e = s.Update(context.Background(), memoryID, "x")
			case "pinMissing":
				_, e = s.Pin(context.Background(), memoryID, true)
			default:
				e = s.Delete(context.Background(), memoryID)
			}
			if (e != nil) != strings.HasSuffix(mode, "Missing") {
				t.Fatal(e)
			}
		})
	}
}
func TestMemoryBoundaryAndReadFailures(t *testing.T) {
	ctx := context.Background()
	s := &Store{pool: contractPool(t)}
	for _, content := range []string{"", strings.Repeat("界", 2001)} {
		if _, e := s.Create(ctx, content); e == nil {
			t.Fatal("invalid create")
		}
		if _, e := s.Update(ctx, memoryID, content); e == nil {
			t.Fatal("invalid update")
		}
	}
	for _, id := range []string{"bad", strings.Repeat("a", 65)} {
		if _, e := s.Update(ctx, id, "x"); e == nil {
			t.Fatal("invalid update id")
		}
		if _, e := s.Pin(ctx, id, true); e == nil {
			t.Fatal("invalid pin id")
		}
		if e := s.Delete(ctx, id); e == nil {
			t.Fatal("invalid delete id")
		}
	}
	for _, phase := range []string{"denied", "query", "scan", "rows"} {
		t.Run(phase, func(t *testing.T) {
			q := queryStep("SELECT")
			if phase == "denied" {
				q.rows = [][]driver.Value{sqlValues(false)}
			}
			if phase == "query" {
				q.err = errors.New("offline")
			}
			if phase == "scan" {
				q.rows = [][]driver.Value{sqlValues(true, true)}
			}
			if phase == "rows" {
				q.rowErr = errors.New("iterator")
			}
			pool := contractPool(t, q)
			if phase == "denied" {
				if _, e := NewStore(ctx, pool); e == nil {
					t.Fatal("allowed")
				}
			} else {
				if _, e := (&Store{pool: pool}).List(ctx); e == nil {
					t.Fatal("read error hidden")
				}
			}
		})
	}
}
func validMemoryEmbedding() embeddingFunc {
	return func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		v := make([]float32, 1024)
		v[0] = 1
		return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Vector: v}}}, nil
	}
}
func TestMemoryEmbeddingSupersessionAndBackfillContract(t *testing.T) {
	ctx := context.Background()
	steps := []sqlStep{queryStep("content_hash"), terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("INSERT INTO memory", memoryRow()), queryStep("LIMIT 1 FOR UPDATE", sqlValues("older", true)), execStep("SET superseded_by"), execStep("SET embedding"), terminalStep("commit"), queryStep("1-(embedding", sqlValues(memoryID, 0.95), sqlValues("nan", math.NaN())), queryStep("embedded_at IS NULL", sqlValues(memoryID, "记忆内容", "hash")), execStep("content_hash=$4"), queryStep("embedded_at IS NULL")}
	s := &Store{pool: contractPool(t, steps...)}
	s.UseEmbedding(validMemoryEmbedding(), "contract", 0.9)
	a, e := s.Create(ctx, "记忆内容")
	if e != nil || !a.Pinned {
		t.Fatal(a, e)
	}
	scores := s.similarities(ctx, "[1]", 3)
	if scores[memoryID] != 0.95 || len(scores) != 1 {
		t.Fatal(scores)
	}
	if e := s.Backfill(ctx, 5); e != nil {
		t.Fatal(e)
	}
}
func TestMemoryBackfillFailuresAndSnapshotFence(t *testing.T) {
	for _, mode := range []string{"noProgress", "threeFailures", "query", "scan", "iterator", "write"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("embedded_at IS NULL", sqlValues(memoryID, "text", "hash"))
			if mode == "threeFailures" {
				q.rows = append(q.rows, q.rows[0], q.rows[0])
			}
			if mode == "query" {
				q.err = errors.New("query")
			}
			if mode == "scan" {
				q.rows = [][]driver.Value{sqlValues(true)}
			}
			if mode == "iterator" {
				q.rowErr = errors.New("iterator")
			}
			steps := []sqlStep{q}
			if mode == "noProgress" || mode == "write" {
				x := execStep("content_hash=$4")
				x.affected = 0
				if mode == "write" {
					x.err = errors.New("write")
				}
				steps = append(steps, x)
			}
			s := &Store{pool: contractPool(t, steps...)}
			s.UseEmbedding(validMemoryEmbedding(), "fixture", 0.9)
			if mode == "threeFailures" {
				s.embedding = embeddingFunc(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
					return model.EmbeddingResponse{}, errors.New("model")
				})
			}
			e := s.Backfill(context.Background(), 4)
			want := mode == "query" || mode == "scan" || mode == "iterator" || mode == "write"
			if (e != nil) != want {
				t.Fatal(e)
			}
		})
	}
}
func TestMemoryHTTPRoutingContract(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	for _, tc := range []struct {
		method, path, body, accept string
		status                     int
	}{
		{"GET", path, "", "application/json", 200}, {"HEAD", path, "", "application/json", 200}, {"PUT", path, "", "", 405}, {"GET", path, "", "text/html", 406}, {"GET", path + "/", "", "", 404}, {"PATCH", path + "/bad/unknown", "{}", "", 404}, {"GET", path + "/bad/pin", "", "", 405}, {"PATCH", path + "/bad/pin", "null", "", 400}, {"PATCH", path + "/bad/pin", `{"pinned":{}}`, "", 400}, {"PATCH", path + "/bad", `{"content":{}}`, "", 400}, {"POST", path, `{"content":" "}`, "", 400}, {"DELETE", path + "/bad", "", "", 400},
	} {
		t.Run(tc.method+tc.path+tc.body, func(t *testing.T) {
			steps := []sqlStep{}
			if tc.status == 200 {
				steps = append(steps, queryStep("ORDER BY pinned", memoryRow()))
			}
			h := NewHTTP(&Store{pool: contractPool(t, steps...)}, logger)
			r := httptest.NewRequest(tc.method, tc.path, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("Accept", tc.accept)
			w := httptest.NewRecorder()
			h.ServeHTTP(w, r)
			if w.Code != tc.status {
				t.Fatal(w.Code, w.Body.String())
			}
		})
	}
	if !OwnsPath(path) || !OwnsPath(path+"/x") || OwnsPath("/api/v1/memoriesX") {
		t.Fatal("ownership")
	}
}
