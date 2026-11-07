package knowledge

import (
	"context"
	"database/sql/driver"
	"errors"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"testing"
)

// Every storage boundary fails in isolation; the strict script rejects later writes.
func TestIndexJobStorageFailureBoundaries(t *testing.T) {
	ctx := context.Background()
	claim := &Claim{Job: Job{ID: contractDocA, DocumentID: contractDocB}, Token: "token"}
	cases := []struct {
		name  string
		steps []sqlStep
		call  func(*Jobs) error
	}{
		{"submit", []sqlStep{terminalStep("begin"), execStep("pg_advisory"), queryStep("SELECT digest"), queryStep("SELECT count", sqlValues(0)), queryStep("SELECT EXISTS", sqlValues(true)), queryStep("INSERT INTO knowledge_index_job", jobRow(contractDocA, contractDocB, "PENDING", 0)), terminalStep("commit")}, func(j *Jobs) error {
			_, e := j.Submit(ctx, contractDocA, contractDocB, "x.txt", nil, []byte("content"))
			return e
		}},
		{"claim", claimSteps(true), func(j *Jobs) error { _, e := j.Claim(ctx); return e }},
		{"recent", []sqlStep{queryStep("ORDER BY created_at", jobRow(contractDocA, contractDocB, "RUNNING", 1))}, func(j *Jobs) error { _, e := j.Recent(ctx); return e }},
		{"renew", []sqlStep{execStep("SET lease_until")}, func(j *Jobs) error { _, e := j.Renew(ctx, claim); return e }},
		{"list-documents", []sqlStep{queryStep("ORDER BY created_at", documentRow(contractDocA))}, func(j *Jobs) error { _, e := j.Store.List(ctx); return e }},
		{"search-documents", []sqlStep{queryStep("SELECT count", sqlValues(1)), queryStep("LIMIT", documentRow(contractDocA))}, func(j *Jobs) error { _, e := j.Store.SearchDocuments(ctx, "query", 0, 10); return e }},
	}
	for _, tc := range cases {
		for i, target := range tc.steps {
			if target.op == "commit" || target.op == "begin" {
				continue
			}
			for _, fault := range []string{"statement", "rows", "scan"} {
				if target.op != "query" && fault != "statement" {
					continue
				}
				t.Run(tc.name+target.match+fault, func(t *testing.T) {
					steps := append([]sqlStep{}, tc.steps[:i+1]...)
					failure := errors.New("storage failed")
					switch fault {
					case "statement":
						steps[i].err = failure
					case "rows":
						steps[i].rows = nil
						steps[i].rowErr = failure
					case "scan":
						steps[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					if tc.steps[0].op == "begin" {
						steps = append(steps, terminalStep("rollback"))
					}
					if e := tc.call(&Jobs{Store: contractStore(t, steps...)}); e == nil {
						t.Fatal("failure swallowed")
					}
				})
			}
		}
	}
}
func TestIndexWorkerProgressAndFencingFailuresReleaseLease(t *testing.T) {
	for _, stage := range []string{"lock", "parse", "embed", "save", "claim-check", "fence", "persist", "receipt"} {
		t.Run(stage, func(t *testing.T) {
			failure := errors.New("storage failed")
			steps := append([]sqlStep{}, claimSteps(true)...)
			if stage == "lock" {
				bad := terminalStep("begin")
				bad.err = failure
				steps = append(steps, bad)
			} else {
				steps = append(steps, acquireDocumentSteps()...)
				for _, name := range []string{"parse", "embed", "save"} {
					step := execStep("SET progress=$3")
					if name == stage {
						step.err = failure
					}
					steps = append(steps, step)
					if name == stage {
						break
					}
				}
				if stage == "claim-check" || stage == "fence" || stage == "persist" || stage == "receipt" {
					steps = append(steps, terminalStep("begin"))
					read := queryStep("SELECT id::text FROM knowledge_index_job", sqlValues(contractDocA))
					if stage == "claim-check" {
						read.err = failure
					}
					steps = append(steps, read)
					if stage != "claim-check" {
						fence := queryStep("SELECT slot FROM runtime_lease", sqlValues(0))
						if stage == "fence" {
							fence.err = failure
						}
						steps = append(steps, fence)
					}
					if stage == "persist" || stage == "receipt" {
						doc := queryStep("INSERT INTO knowledge_document", documentRow(contractDocA))
						if stage == "persist" {
							doc.err = failure
						}
						steps = append(steps, doc)
					}
					if stage == "receipt" {
						steps = append(steps, sqlStep{op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"))
						receipt := execStep("SET status='SUCCEEDED'")
						receipt.err = failure
						steps = append(steps, receipt)
					}
					steps = append(steps, terminalStep("rollback"))
				}
				steps = append(steps, releaseDocumentSteps()...)
			}
			steps = append(steps, execStep("SET status=$3"))
			s := contractStore(t, steps...)
			locks, e := coordination.New(s.Pool)
			if e != nil {
				t.Fatal(e)
			}
			defer locks.Close(context.Background())
			s.Locks = locks
			contractEmbedding(t, s)
			released := 0
			j := &Jobs{Store: s, Stats: NewStats(), Acquire: func(context.Context) (func(), error) { return func() { released++ }, nil }}
			ran, e := j.RunNext(context.Background())
			if !ran || e != nil || released != 1 || j.Stats.Active.Load() != 0 {
				t.Fatal(ran, e, released)
			}
			counts, _ := j.Stats.Snapshot()
			if counts["jobs.attempts|retry"] != 1 {
				t.Fatal(counts)
			}
		})
	}
}
