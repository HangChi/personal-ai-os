package knowledge

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
)

func TestGovernanceReadFailuresDoNotReturnPartialEvidence(t *testing.T) {
	ctx := context.Background()
	for _, operation := range []string{"spaces", "sources", "enrich", "conflicts", "expand", "apply"} {
		for _, failure := range []string{"query", "scan", "rows"} {
			t.Run(operation+"/"+failure, func(t *testing.T) {
				match := "FROM knowledge_conflict"
				if operation == "spaces" {
					match = "FROM knowledge_space"
				}
				if operation == "sources" || operation == "enrich" {
					match = "FROM knowledge_source"
				}
				step := queryStep(match)
				switch failure {
				case "query":
					step.err = errors.New("query failed")
				case "scan":
					step.rows = [][]driver.Value{sqlValues(nil)}
				case "rows":
					step.rowErr = errors.New("rows failed")
				}
				g := &Governance{contractStore(t, step)}
				var e error
				switch operation {
				case "spaces":
					_, e = g.Spaces(ctx)
				case "sources":
					_, e = g.Sources(ctx)
				case "enrich":
					_, e = g.Enrich(ctx, governanceHits())
				case "conflicts":
					_, e = g.Conflicts(ctx)
				case "expand":
					_, e = g.Expand(ctx, governanceHits())
				case "apply":
					warnings := []string{}
					_, e = g.ApplyDecisions(ctx, Review{}, governanceHits(), &warnings)
				}
				if e == nil {
					t.Fatal("storage failure swallowed")
				}
			})
		}
	}
}

func TestGovernanceMutationFailuresRollbackAtEachStatement(t *testing.T) {
	ctx := context.Background()
	h := governanceHits()
	cases := []struct {
		name  string
		steps []sqlStep
		run   func(*Governance) error
	}{
		{"metadata", []sqlStep{terminalStep("begin"), queryStep("EXISTS(SELECT 1 FROM knowledge_space", sqlValues(true)), execStep("UPDATE knowledge_source SET space_id"), queryStep("WHERE s.document_id IN", sourceRow(contractDocA, "ACTIVE", 2)), terminalStep("commit")}, func(g *Governance) error {
			_, e := g.Update(ctx, contractDocA, Metadata{SpaceID: contractChunkA, Lifecycle: "ACTIVE"})
			return e
		}},
		{"resolution", []sqlStep{terminalStep("begin"), queryStep("SELECT left_id::text,right_id::text,status", sqlValues(contractDocA, contractDocB, "OPEN")), execStep("UPDATE knowledge_conflict SET status='RESOLVED'"), execStep("INSERT INTO knowledge_conflict_audit"), terminalStep("commit")}, func(g *Governance) error {
			return g.Resolve(ctx, "c", Resolution{Decision: "KEEP_BOTH", Reason: "reviewed"})
		}},
		{"record", []sqlStep{terminalStep("begin"), queryStep("SELECT document_id::text", sqlValues(contractDocA)), queryStep("SELECT document_id::text", sqlValues(contractDocB)), queryStep("SELECT s.revision,v.content", sqlValues(2, h[0].Content)), queryStep("SELECT s.revision,v.content", sqlValues(3, h[1].Content)), execStep("INSERT INTO knowledge_conflict(fingerprint"), terminalStep("commit")}, func(g *Governance) error {
			return g.Record(ctx, []Disagreement{{h[0], h[1], "每周五天", "每周四天", "conflict"}})
		}},
	}
	for _, tc := range cases {
		for i := range tc.steps {
			t.Run(fmt.Sprintf("%s/%d", tc.name, i), func(t *testing.T) {
				steps := append([]sqlStep{}, tc.steps[:i+1]...)
				steps[i].err = errors.New("write boundary failed")
				if i > 0 && tc.steps[i].op != "commit" {
					steps = append(steps, terminalStep("rollback"))
				}
				if e := tc.run(&Governance{contractStore(t, steps...)}); e == nil {
					t.Fatal("write failure swallowed")
				}
			})
		}
	}
}

func TestGovernanceExpansionFailsClosedOnBrokenChunks(t *testing.T) {
	for _, failure := range []string{"query", "json", "source"} {
		t.Run(failure, func(t *testing.T) {
			steps := []sqlStep{queryStep("WHERE status IN", conflictRow("OPEN", nil, nil, nil))}
			chunk := queryStep("WHERE metadata->>'document_id'", sqlValues(contractChunkB, "每周四天工作制", contractMetadata(contractDocB, 1)))
			if failure == "query" {
				chunk.err = errors.New("chunk query failed")
			}
			if failure == "json" {
				chunk.rows[0][2] = "{"
			}
			steps = append(steps, chunk)
			if failure == "source" {
				q := queryStep("WHERE s.document_id IN")
				q.err = errors.New("source failed")
				steps = append(steps, q)
			}
			if _, e := (&Governance{contractStore(t, steps...)}).Expand(context.Background(), governanceHits()[:1]); e == nil {
				t.Fatal("broken evidence accepted")
			}
		})
	}
	// Unrelated findings are ignored; expansion is bounded even with many duplicates.
	unrelated := conflictRow("OPEN", nil, nil, nil)
	unrelated[1] = contractChunkA
	unrelated[2] = contractChunkB
	rows := [][]driver.Value{unrelated}
	for i := 0; i < 13; i++ {
		rows = append(rows, conflictRow("OPEN", nil, nil, nil))
	}
	g := &Governance{contractStore(t, queryStep("WHERE status IN", rows...))}
	if out, e := g.Expand(context.Background(), governanceHits()); e != nil || len(out) != 2 {
		t.Fatal(out, e)
	}
}
