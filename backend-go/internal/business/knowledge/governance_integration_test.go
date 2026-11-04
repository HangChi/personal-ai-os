//go:build integration

package knowledge

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"sync"
	"testing"
	"time"
)

func fixtureGoverned(t *testing.T) (*Store, *Governance, []Hit) {
	t.Helper()
	s := fixtureStore(t)
	ctx := context.Background()
	g := &Governance{Store: s}
	var hits []Hit
	for i, text := range []string{"每周工作五天，每天八小时。", "每周工作四天，每天六小时。"} {
		name := []string{"left.txt", "right.txt"}[i]
		d, e := s.Ingest(ctx, name, "", []byte(text))
		if e != nil {
			t.Fatal(e)
		}
		h, e := g.documentChunk(ctx, d.ID, 0)
		if e != nil || len(h) != 1 {
			t.Fatal(h, e)
		}
		hits = append(hits, h[0])
	}
	return s, g, hits
}
func TestP6GovernanceSourceMetadataConflictAuditAndInvalidation(t *testing.T) {
	s, g, hits := fixtureGoverned(t)
	ctx := context.Background()
	space, e := g.CreateSpace(ctx, " 政策 ")
	if e != nil || space.Name != "政策" {
		t.Fatal(space, e)
	}
	if _, e = g.CreateSpace(ctx, "政策"); e == nil {
		t.Fatal("duplicate")
	}
	from, until := "2026-01-01", "2026-12-31"
	m := Metadata{SpaceID: space.ID, Version: "v2", Scope: "工程部", Authority: "正式制度", Lifecycle: "ACTIVE", EffectiveFrom: &from, EffectiveUntil: &until}
	source, e := g.Update(ctx, hits[0].DocumentID, m)
	if e != nil || source.Revision <= hits[0].Source.Revision || source.SpaceName != "政策" || source.ReviewState != "PENDING" {
		t.Fatal(source, e)
	}
	newer, e := g.Enrich(ctx, hits)
	if e != nil {
		t.Fatal(e)
	}
	c := Disagreement{newer[0], newer[1], "每周工作五天", "每周工作四天", "同范围不同说法"}
	if e = g.Record(ctx, []Disagreement{c, c}); e != nil {
		t.Fatal(e)
	}
	conflicts, e := g.Conflicts(ctx)
	if e != nil || len(conflicts) != 1 || conflicts[0].Status != "OPEN" {
		t.Fatal(conflicts, e)
	}
	// A later model omission cannot erase the persisted open finding.
	warnings := []string{}
	review, e := g.ApplyDecisions(ctx, Review{Relevant: newer, Conflicts: []Disagreement{}}, newer, &warnings)
	if e != nil || len(review.Conflicts) != 1 {
		t.Fatal(review, e)
	}
	conflict := conflicts[0]
	bad := newID()
	if e = g.Resolve(ctx, conflict.ID, Resolution{"PREFER_SOURCE", &bad, "依据"}); e == nil {
		t.Fatal("foreign winner")
	}
	winner := newer[0].DocumentID
	if e = g.Resolve(ctx, conflict.ID, Resolution{"PREFER_SOURCE", &winner, "核对正式制度"}); e != nil {
		t.Fatal(e)
	}
	review, e = g.ApplyDecisions(ctx, Review{Relevant: newer, Conflicts: []Disagreement{c}}, newer, &warnings)
	if e != nil || len(review.Conflicts) != 0 || len(review.Relevant) != 1 || review.Relevant[0].DocumentID != winner || len(warnings) != 1 {
		t.Fatal(review, warnings, e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_conflict_audit WHERE conflict_id=$1", conflict.ID) != 1 {
		t.Fatal("no audit")
	}
	if e = g.Resolve(ctx, conflict.ID, Resolution{"NOT_CONFLICT", nil, "repeat"}); e == nil {
		t.Fatal("resolved twice")
	}
	expanded, e := g.Expand(ctx, newer[:1])
	if e != nil || len(expanded) != 2 {
		t.Fatal(expanded, e)
	}
	m.Version = "v3"
	if _, e = g.Update(ctx, newer[0].DocumentID, m); e != nil {
		t.Fatal(e)
	}
	if scalar[string](t, s.Pool, "SELECT status FROM knowledge_conflict WHERE id=$1", conflict.ID) != "STALE" {
		t.Fatal("not invalidated")
	}
	if e = g.Record(ctx, []Disagreement{c}); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_conflict") != 1 {
		t.Fatal("stale result recorded")
	}
	m.Lifecycle = "SUPERSEDED"
	if _, e = g.Update(ctx, newer[0].DocumentID, m); e != nil {
		t.Fatal(e)
	}
	active, e := g.Enrich(ctx, newer)
	if e != nil || len(active) != 1 {
		t.Fatal(active, e)
	}
}
func TestP6GovernanceRejectsForgedQuotesAndKeepBoth(t *testing.T) {
	s, g, hits := fixtureGoverned(t)
	ctx := context.Background()
	forged := Disagreement{hits[0], hits[1], "模型虚构的摘录", "每周工作四天", "invalid"}
	if e := g.Record(ctx, []Disagreement{forged}); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_conflict") != 0 {
		t.Fatal("forged conflict recorded")
	}
	c := Disagreement{hits[0], hits[1], "每周工作五天", "每周工作四天", "different"}
	if e := g.Record(ctx, []Disagreement{c}); e != nil {
		t.Fatal(e)
	}
	rows, _ := g.Conflicts(ctx)
	if e := g.Resolve(ctx, rows[0].ID, Resolution{"KEEP_BOTH", nil, "继续保留核查"}); e != nil {
		t.Fatal(e)
	}
	warnings := []string{}
	review, e := g.ApplyDecisions(ctx, Review{Relevant: hits}, hits, &warnings)
	if e != nil || len(review.Conflicts) != 1 || len(review.Relevant) != 2 {
		t.Fatal(review, e)
	}
}

func TestP6GovernanceOutageRetainsEvidenceAndGuardsAnswer(t *testing.T) {
	s, g, hits := fixtureGoverned(t)
	model := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
		if strings.Contains(rules, "queries") {
			return json.RawMessage(`{"queries":[]}`), nil
		}
		if strings.Contains(rules, "order") {
			if e := s.Pool.Close(); e != nil {
				t.Fatal(e)
			}
			return json.RawMessage(`{"order":[1,2]}`), nil
		}
		t.Fatal("governance outage must not proceed to model evidence approval")
		return nil, nil
	})
	p := &Pipeline{Recall: &recallFixture{hits: hits}, Model: model, Review: &EvidenceReviewer{model}, Governance: g, Enabled: true, TopK: 1}
	result, e := p.Retrieve(context.Background(), "每周工作几天", nil)
	if e != nil || result.Status != "UNVERIFIED" || !result.Guarded() || len(result.Hits) != 2 || len(result.Warnings) == 0 {
		t.Fatal(result, e)
	}
}
func TestP6ReviewClaimCompetitionRevisionAndLeaseFencing(t *testing.T) {
	s, g, hits := fixtureGoverned(t)
	ctx := context.Background()
	execSQL(t, s.Pool, "DELETE FROM knowledge_review_job WHERE document_id=$1", hits[1].DocumentID)
	j := &ReviewJobs{Store: s, Governance: g, Stats: NewStats()}
	var mu sync.Mutex
	claims := []*ReviewClaim{}
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Go(func() {
			c, e := j.Claim(ctx)
			if e != nil {
				t.Error(e)
				return
			}
			if c != nil {
				mu.Lock()
				claims = append(claims, c)
				mu.Unlock()
			}
		})
	}
	wg.Wait()
	if len(claims) != 1 {
		t.Fatal(len(claims))
	}
	old := claims[0]
	execSQL(t, s.Pool, "UPDATE knowledge_review_job SET next_attempt_at=now()-interval '1 second'")
	fresh, e := j.Claim(ctx)
	if e != nil || fresh == nil {
		t.Fatal(e)
	}
	if e = j.Finish(ctx, old, nil, nil, true, false); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_review_job") != 1 {
		t.Fatal("old worker completed job")
	}
	execSQL(t, s.Pool, "UPDATE knowledge_source SET authority='changed' WHERE document_id=$1", fresh.DocumentID)
	if e = j.Finish(ctx, fresh, nil, nil, true, false); e != nil {
		t.Fatal(e)
	}
	if scalar[string](t, s.Pool, "SELECT review_state FROM knowledge_source WHERE document_id=$1", fresh.DocumentID) != "PENDING" {
		t.Fatal("old revision completed source")
	}
	fresh, e = j.Claim(ctx)
	if e != nil || fresh == nil {
		t.Fatal(e)
	}
	if e = j.Finish(ctx, fresh, nil, nil, true, false); e != nil {
		t.Fatal(e)
	}
	if scalar[string](t, s.Pool, "SELECT review_state FROM knowledge_source WHERE document_id=$1", fresh.DocumentID) != "CHECKED_CANDIDATES" {
		t.Fatal("finish state")
	}
}
func TestP6ReviewIncrementalFailureRetryAndSourceRevision(t *testing.T) {
	s, g, hits := fixtureGoverned(t)
	ctx := context.Background()
	execSQL(t, s.Pool, "DELETE FROM knowledge_review_job WHERE document_id=$1", hits[1].DocumentID)
	calls := 0
	structured := structuredFunc(func(_ context.Context, _ string, p any, _ time.Duration) (json.RawMessage, error) {
		calls++
		sources := p.(map[string]any)["sources"].([]any)
		n := len(sources)
		list := []Hit{}
		for i := 0; i < n; i++ {
			list = append(list, Hit{})
		}
		return reviewJSON(list, `[{"left":1,"right":2,"leftQuote":"每周工作五天","rightQuote":"每周工作四天","reason":"不同制度"}]`, ""), nil
	})
	j := &ReviewJobs{Store: s, Governance: g, Recall: &recallFixture{hits: hits}, Reviewer: &EvidenceReviewer{structured}}
	if worked, e := j.RunNext(ctx); e != nil || !worked {
		t.Fatal(worked, e)
	}
	if calls != 1 || scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_conflict") != 1 {
		t.Fatal(calls)
	}
	if scalar[string](t, s.Pool, "SELECT review_state FROM knowledge_source WHERE document_id=$1", hits[0].DocumentID) != "PARTIAL" {
		t.Fatal("not partial")
	}
	if worked, e := j.RunNext(ctx); e != nil || !worked {
		t.Fatal(worked, e)
	}
	if scalar[int](t, s.Pool, "SELECT count(*) FROM knowledge_review_job") != 0 {
		t.Fatal("not completed")
	}
	if e := g.Retry(ctx, hits[0].DocumentID); e != nil {
		t.Fatal(e)
	}
	j.Reviewer = &EvidenceReviewer{structuredFunc(func(context.Context, string, any, time.Duration) (json.RawMessage, error) {
		return nil, errors.New("secret upstream")
	})}
	for i := 1; i <= 3; i++ {
		if _, e := j.RunNext(ctx); e != nil {
			t.Fatal(e)
		}
		if i < 3 {
			execSQL(t, s.Pool, "UPDATE knowledge_review_job SET next_attempt_at=now()")
		}
	}
	if !scalar[bool](t, s.Pool, "SELECT next_attempt_at='infinity'::timestamptz FROM knowledge_review_job WHERE document_id=$1", hits[0].DocumentID) {
		t.Fatal("not capped")
	}
	if e := g.Retry(ctx, hits[0].DocumentID); e != nil {
		t.Fatal(e)
	}
	if scalar[int](t, s.Pool, "SELECT attempts FROM knowledge_review_job WHERE document_id=$1", hits[0].DocumentID) != 0 {
		t.Fatal("retry not reset")
	}
}
