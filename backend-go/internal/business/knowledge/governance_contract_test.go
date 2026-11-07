package knowledge

import (
	"context"
	"database/sql/driver"
	"errors"
	"reflect"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/api"
)

func sourceRow(id, lifecycle string, revision int) []driver.Value {
	return sqlValues(id, "文档", contractChunkA, "资料库", "v1", "适用范围", nil, nil, "权威", lifecycle, revision, "PARTIAL")
}
func conflictRow(status string, resolution, winner, reason any) []driver.Value {
	return sqlValues("conflict", contractDocA, contractDocB, 0, 1, "每周五天", "每周四天", "制度不同", status, resolution, winner, reason, 2, 3)
}
func governanceHits() []Hit {
	return []Hit{{DocumentID: contractDocA, ChunkIndex: 0, Content: "每周五天工作制", Source: &SourceInfo{Revision: 2}}, {DocumentID: contractDocB, ChunkIndex: 1, Content: "每周四天工作制", Source: &SourceInfo{Revision: 3}}}
}
func TestGovernanceSpacesEnrichmentAndMetadataWrites(t *testing.T) {
	ctx := context.Background()
	t.Run("spaces", func(t *testing.T) {
		insert := execStep("INSERT INTO knowledge_space")
		insert.check = func(a []driver.NamedValue) {
			if a[1].Value != "资料库" {
				t.Fatal(a)
			}
		}
		dup := execStep("INSERT INTO knowledge_space")
		dup.err = &pgconn.PgError{Code: "23505"}
		s := contractStore(t, queryStep("ORDER BY name", sqlValues(contractChunkA, "已有库")), insert, dup)
		g := &Governance{s}
		spaces, e := g.Spaces(ctx)
		if e != nil || len(spaces) != 1 || spaces[0].Name != "已有库" {
			t.Fatal(spaces, e)
		}
		made, e := g.CreateSpace(ctx, " 资料库 ")
		if e != nil || made.ID == "" || made.Name != "资料库" {
			t.Fatal(made, e)
		}
		_, e = g.CreateSpace(ctx, "资料库")
		expectBusiness(t, e, api.ValidationFailed, "已存在")
		for _, name := range []string{" ", strings.Repeat("字", 121)} {
			_, e = g.CreateSpace(ctx, name)
			expectBusiness(t, e, api.ValidationFailed, "名称")
		}
	})
	t.Run("enrich-only-current-sources", func(t *testing.T) {
		sources := queryStep("WHERE s.document_id IN", sourceRow(contractDocA, "ACTIVE", 2), sourceRow(contractDocB, "SUPERSEDED", 3))
		sources.check = func(a []driver.NamedValue) {
			if len(a) != 2 {
				t.Fatal(a)
			}
		}
		s := contractStore(t, sources, queryStep("ORDER BY d.updated_at", sourceRow(contractDocA, "ACTIVE", 2)))
		hits := append(governanceHits(), governanceHits()[0], Hit{Content: "orphan"})
		g := &Governance{s}
		out, e := g.Enrich(ctx, hits)
		if e != nil || len(out) != 2 || out[0].Source.SpaceName != "资料库" || out[1].DocumentID != contractDocA {
			t.Fatal(out, e)
		}
		if hits[0].Source.SpaceName != "" {
			t.Fatal("input changed")
		}
		all, e := g.Sources(ctx)
		if e != nil || len(all) != 1 {
			t.Fatal(all, e)
		}
	})
	for _, mode := range []string{"updated", "space-missing", "document-missing", "write-error"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), queryStep("EXISTS(SELECT 1 FROM knowledge_space", sqlValues(mode != "space-missing"))}
			if mode != "space-missing" {
				write := execStep("UPDATE knowledge_source SET space_id")
				write.check = func(a []driver.NamedValue) {
					if a[1].Value != "v2" || a[2].Value != "范围" || a[5].Value != "来源" {
						t.Fatal(a)
					}
				}
				if mode == "document-missing" {
					write.affected = 0
				}
				if mode == "write-error" {
					write.err = errors.New("write failed")
				}
				steps = append(steps, write)
			}
			if mode == "updated" {
				steps = append(steps, queryStep("WHERE s.document_id IN", sourceRow(contractDocA, "ACTIVE", 4)), terminalStep("commit"))
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			g := &Governance{contractStore(t, steps...)}
			got, e := g.Update(ctx, contractDocA, Metadata{SpaceID: contractChunkA, Version: " v2 ", Scope: " 范围 ", Authority: " 来源 ", Lifecycle: "ACTIVE"})
			if mode == "updated" {
				if e != nil || got.Revision != 4 {
					t.Fatal(got, e)
				}
			} else if e == nil {
				t.Fatal("missing error")
			}
		})
	}
}
func TestGovernanceRejectsInvalidMetadataBeforeWriting(t *testing.T) {
	str := func(v string) *string { return &v }
	for _, mutate := range []func(*Metadata){func(m *Metadata) { m.SpaceID = "bad" }, func(m *Metadata) { m.Lifecycle = "DELETED" }, func(m *Metadata) { m.EffectiveFrom = str("2026-02-30") }, func(m *Metadata) { m.EffectiveUntil = str("bad") }, func(m *Metadata) { m.EffectiveFrom = str("2026-11-02"); m.EffectiveUntil = str("2026-11-01") }, func(m *Metadata) { m.Version = strings.Repeat("a", 121) }, func(m *Metadata) { m.Scope = strings.Repeat("a", 501) }, func(m *Metadata) { m.Authority = strings.Repeat("a", 301) }} {
		m := Metadata{SpaceID: contractChunkA, Lifecycle: "ACTIVE"}
		mutate(&m)
		_, e := (&Governance{contractStore(t)}).Update(context.Background(), contractDocA, m)
		expectBusiness(t, e, api.ValidationFailed, "")
	}
}
func TestGovernanceResolveAuditIsAtomicAndWinnerIsValidated(t *testing.T) {
	for _, mode := range []string{"prefer-left", "prefer-right", "keep", "not-conflict", "missing", "closed", "bad-winner", "audit-error"} {
		t.Run(mode, func(t *testing.T) {
			read := queryStep("SELECT left_id::text,right_id::text,status", sqlValues(contractDocA, contractDocB, "OPEN"))
			if mode == "missing" {
				read.rows = nil
			}
			if mode == "closed" {
				read.rows[0][2] = "STALE"
			}
			decision := "PREFER_SOURCE"
			winner := contractDocA
			if mode == "prefer-right" {
				winner = contractDocB
			}
			if mode == "bad-winner" {
				winner = contractChunkA
			}
			if mode == "keep" {
				decision = "KEEP_BOTH"
			}
			if mode == "not-conflict" {
				decision = "NOT_CONFLICT"
			}
			steps := []sqlStep{terminalStep("begin"), read}
			valid := mode != "missing" && mode != "closed" && mode != "bad-winner"
			if valid {
				update := execStep("UPDATE knowledge_conflict SET status='RESOLVED'")
				update.check = func(a []driver.NamedValue) {
					if a[0].Value != decision || a[2].Value != "人工复核" {
						t.Fatal(a)
					}
					if decision != "PREFER_SOURCE" && a[1].Value != nil {
						t.Fatal("winner leaked", a)
					}
				}
				audit := execStep("INSERT INTO knowledge_conflict_audit")
				if mode == "audit-error" {
					audit.err = errors.New("audit failed")
				}
				steps = append(steps, update, audit)
			}
			if valid && mode != "audit-error" {
				steps = append(steps, terminalStep("commit"))
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			g := &Governance{contractStore(t, steps...)}
			e := g.Resolve(context.Background(), "c", Resolution{Decision: decision, WinnerID: &winner, Reason: " 人工复核 "})
			if (e == nil) != (valid && mode != "audit-error") {
				t.Fatal(e)
			}
		})
	}
	for _, r := range []Resolution{{Decision: "INVALID", Reason: "x"}, {Decision: "KEEP_BOTH", Reason: " "}, {Decision: "KEEP_BOTH", Reason: strings.Repeat("x", 1001)}} {
		if e := (&Governance{contractStore(t)}).Resolve(context.Background(), "c", r); e == nil {
			t.Fatal(r)
		}
	}
}
func TestGovernanceRecordCanonicalIdentityAndRejectStaleEvidence(t *testing.T) {
	for _, mode := range []string{"reverse", "revision-changed", "quote-changed", "missing-chunk", "query-error", "insert-error"} {
		t.Run(mode, func(t *testing.T) {
			h := governanceHits()
			left := queryStep("SELECT document_id::text FROM knowledge_source", sqlValues(contractDocA))
			left.check = func(a []driver.NamedValue) {
				if a[0].Value != contractDocA {
					t.Fatal("locks not sorted", a)
				}
			}
			steps := []sqlStep{terminalStep("begin"), left, queryStep("SELECT document_id::text FROM knowledge_source", sqlValues(contractDocB))}
			first := queryStep("SELECT s.revision,v.content", sqlValues(2, h[0].Content))
			if mode == "revision-changed" {
				first.rows[0][0] = int64(9)
			}
			if mode == "quote-changed" {
				first.rows[0][1] = "无关内容"
			}
			if mode == "missing-chunk" {
				first.rows = nil
			}
			if mode == "query-error" {
				first.err = errors.New("db failed")
			}
			steps = append(steps, first)
			if mode == "reverse" || mode == "insert-error" {
				write := execStep("INSERT INTO knowledge_conflict(fingerprint")
				write.check = func(a []driver.NamedValue) {
					want := digest(contractDocA + ":2:" + contractDocB + ":3:每周五天:每周四天")
					if a[0].Value != want || a[1].Value != contractDocA || a[7].Value != "每周五天" {
						t.Fatal(a)
					}
				}
				if mode == "insert-error" {
					write.err = errors.New("insert failed")
				}
				steps = append(steps, queryStep("SELECT s.revision,v.content", sqlValues(3, h[1].Content)), write)
			}
			fails := mode == "query-error" || mode == "insert-error"
			if fails {
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, terminalStep("commit"))
			}
			g := &Governance{contractStore(t, steps...)}
			e := g.Record(context.Background(), []Disagreement{{h[1], h[0], "每周四天", "每周五天", "制度不同"}})
			if (e != nil) != fails {
				t.Fatal(e)
			}
		})
	}
	g := &Governance{contractStore(t, terminalStep("begin"), terminalStep("commit"))}
	if e := g.Record(context.Background(), []Disagreement{{}}); e != nil {
		t.Fatal(e)
	}
	if e := g.Record(context.Background(), nil); e != nil {
		t.Fatal(e)
	}
}
func TestGovernanceApplyHumanDecisionsPreservesQuestionScopeAndRevisions(t *testing.T) {
	for _, mode := range []string{"open", "keep", "prefer-left", "prefer-right", "not-conflict", "stale-revision", "unrelated", "existing"} {
		t.Run(mode, func(t *testing.T) {
			h := governanceHits()
			row := conflictRow("OPEN", nil, nil, nil)
			switch mode {
			case "keep":
				row = conflictRow("RESOLVED", "KEEP_BOTH", nil, "保留")
			case "prefer-left":
				row = conflictRow("RESOLVED", "PREFER_SOURCE", contractDocA, "以甲为准")
			case "prefer-right":
				row = conflictRow("RESOLVED", "PREFER_SOURCE", contractDocB, "以乙为准")
			case "not-conflict":
				row = conflictRow("RESOLVED", "NOT_CONFLICT", nil, "适用不同")
			case "stale-revision":
				row[13] = int64(99)
			}
			g := &Governance{contractStore(t, queryStep("WHERE status IN ('OPEN','RESOLVED')", row))}
			original := Review{Relevant: h, Clarification: "请指定版本"}
			if mode == "unrelated" {
				original.Relevant = []Hit{{DocumentID: contractChunkA, ChunkIndex: 9}}
			}
			if mode == "existing" {
				original.Conflicts = []Disagreement{{h[1], h[0], "每周四天", "每周五天", "当前发现"}}
			}
			warnings := []string{}
			got, e := g.ApplyDecisions(context.Background(), original, h, &warnings)
			if e != nil || got.Clarification != original.Clarification {
				t.Fatal(got, e)
			}
			switch mode {
			case "prefer-left", "prefer-right":
				want := contractDocA
				if mode == "prefer-right" {
					want = contractDocB
				}
				if len(got.Conflicts) != 0 || len(got.Relevant) != 1 || got.Relevant[0].DocumentID != want || len(warnings) != 1 {
					t.Fatal(got, warnings)
				}
			case "not-conflict":
				if len(got.Conflicts) != 0 || len(got.Relevant) != 2 || len(warnings) != 1 {
					t.Fatal(got, warnings)
				}
			case "stale-revision", "unrelated":
				if len(got.Conflicts) != 0 || !reflect.DeepEqual(got.Relevant, original.Relevant) {
					t.Fatal(got)
				}
			default:
				if len(got.Conflicts) != 1 || len(got.Relevant) != 2 {
					t.Fatal(got)
				}
			}
			if len(original.Relevant) != 2 && mode != "unrelated" {
				t.Fatal("input mutated")
			}
		})
	}
}
func TestGovernanceExpandLoadsOtherSideOnceAndRetryIsAtomic(t *testing.T) {
	s := contractStore(t, queryStep("WHERE status IN ('OPEN','RESOLVED')", conflictRow("OPEN", nil, nil, nil)), queryStep("WHERE metadata->>'document_id'", sqlValues(contractChunkB, "每周四天工作制", contractMetadata(contractDocB, 1))), queryStep("WHERE s.document_id IN", sourceRow(contractDocB, "ACTIVE", 3)), terminalStep("begin"), execStep("UPDATE knowledge_source SET review_state='PENDING'"), execStep("INSERT INTO knowledge_review_job"), terminalStep("commit"))
	g := &Governance{s}
	out, e := g.Expand(context.Background(), governanceHits()[:1])
	if e != nil || len(out) != 2 || out[1].DocumentID != contractDocB || out[1].Source.Revision != 3 {
		t.Fatal(out, e)
	}
	if e = g.Retry(context.Background(), contractDocA); e != nil {
		t.Fatal(e)
	}
	if out, e = g.Expand(context.Background(), nil); e != nil || len(out) != 0 {
		t.Fatal(out, e)
	}
}
func TestReviewFinishLeaseAndProgressState(t *testing.T) {
	for _, state := range []string{"PARTIAL", "CHECKED_CANDIDATES", "UNAVAILABLE", "lease-lost"} {
		t.Run(state, func(t *testing.T) {
			owned := queryStep("SELECT j.document_id::text", sqlValues(contractDocA))
			if state == "lease-lost" {
				owned.rows = nil
			}
			steps := []sqlStep{terminalStep("begin"), queryStep("SELECT document_id::text FROM knowledge_source", sqlValues(contractDocA)), owned}
			if state != "lease-lost" {
				write := execStep("UPDATE knowledge_source SET review_state")
				write.check = func(a []driver.NamedValue) {
					if a[0].Value != state {
						t.Fatal(a)
					}
				}
				finish := "UPDATE knowledge_review_job SET chunk_cursor"
				if state == "CHECKED_CANDIDATES" {
					finish = "DELETE FROM knowledge_review_job"
				}
				if state == "UNAVAILABLE" {
					finish = "UPDATE knowledge_review_job SET next_attempt_at"
				}
				steps = append(steps, write, execStep(finish))
			}
			steps = append(steps, terminalStep("commit"))
			s := contractStore(t, steps...)
			j := &ReviewJobs{Store: s, Governance: &Governance{s}, Stats: NewStats()}
			e := j.Finish(context.Background(), &ReviewClaim{DocumentID: contractDocA, Revision: 2, Token: "owned"}, nil, nil, state == "CHECKED_CANDIDATES", state == "UNAVAILABLE")
			if e != nil {
				t.Fatal(e)
			}
			c, _ := j.Stats.Snapshot()
			label := state
			if state == "lease-lost" {
				label = "lease_lost"
			}
			if c["review.results|"+label] != 1 {
				t.Fatal(c)
			}
		})
	}
}
