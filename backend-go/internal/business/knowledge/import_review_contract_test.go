package knowledge

import (
	"bytes"
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/model"
	"strings"
	"testing"
	"time"
)

func importText(s string) *string { return &s }
func importEmbedding() p8BatchEmbedding {
	return func(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		out := model.EmbeddingResponse{}
		for i := range r.Input {
			out.Results = append(out.Results, model.EmbeddingResult{Index: i, Vector: []float32{1, 0}})
		}
		return out, nil
	}
}
func TestKnowledgeImportPreparationContract(t *testing.T) {
	s := contractStore(t)
	s.Embedding = importEmbedding()
	ctx := context.Background()
	n := 42
	meta := &ImportDocumentMeta{ID: importText(contractDocA), Title: importText("Imported"), Filename: importText("source.txt"), ContentType: importText("text/plain"), CharCount: &n}
	out, e := s.PrepareImport(ctx, []*ImportDocument{{}, {Document: &ImportDocumentMeta{}}, {Document: &ImportDocumentMeta{ID: importText(contractDocA)}}, {Document: meta, Chunks: []string{" ", "first", "second"}}})
	if e != nil || len(out) != 1 || out[0].chars != 42 || len(out[0].chunks) != 2 || out[0].title != "Imported" || out[0].chunks[1].Content != "second" {
		t.Fatal(out, e)
	}
	for _, entry := range []*ImportDocument{nil, {Document: &ImportDocumentMeta{ID: importText("invalid")}, Chunks: []string{"x"}}} {
		if _, e = s.PrepareImport(ctx, []*ImportDocument{entry}); e == nil {
			t.Fatal("invalid metadata")
		}
	}
	for _, m := range []*ImportDocumentMeta{{ID: importText(contractDocA)}, {ID: importText(contractDocA), Filename: importText(" "), Title: importText(" "), ContentType: importText(" ")}} {
		v, e := s.PrepareImport(ctx, []*ImportDocument{{Document: m, Chunks: []string{" "}}})
		if e != nil || len(v) != 1 || v[0].filename != "imported.md" || len(v[0].chunks) != 0 {
			t.Fatal(v, e)
		}
	}
	s.Embedding = nil
	if _, e = s.PrepareImport(ctx, []*ImportDocument{{Document: meta, Chunks: []string{"requires embedding"}}}); e == nil {
		t.Fatal("missing embedding")
	}
}
func TestKnowledgeImportPersistsSourceAndRollsBackFailureContract(t *testing.T) {
	for _, mode := range []string{"default", "source", "document", "chunk", "invalid-space", "space", "metadata", "source-write"} {
		t.Run(mode, func(t *testing.T) {
			at := time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC)
			source := &SourceInfo{SpaceName: " imported space ", Lifecycle: "ACTIVE"}
			if mode == "invalid-space" {
				source.SpaceName = " "
			}
			if mode == "metadata" {
				source.Lifecycle = "BAD"
			}
			steps := []sqlStep{terminalStep("begin"), execStep("INSERT INTO knowledge_document"), {op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store")}
			if mode != "default" {
				if mode != "invalid-space" {
					steps = append(steps, queryStep("INSERT INTO knowledge_space", sqlValues(contractChunkA)))
					if mode != "metadata" {
						steps = append(steps, execStep("UPDATE knowledge_source"))
					}
				}
			}
			pos := map[string]int{"document": 1, "chunk": 3, "space": 4, "source-write": 5}
			fail := mode != "default" && mode != "source"
			if i, ok := pos[mode]; ok {
				steps = steps[:i+1]
				steps[i].err = errors.New("injected import failure")
			}
			if fail {
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, terminalStep("commit"))
			}
			s := contractStore(t, steps...)
			d := PreparedImport{meta: ImportDocumentMeta{CreatedAt: &at}, id: contractDocA, filename: "imported.md", contentType: "text/markdown", chunks: []Chunk{{ID: contractChunkA, Content: "imported content", Metadata: map[string]any{}, Vector: []float32{1, 0}}}}
			if mode != "default" {
				d.title = "title"
				d.source = source
			}
			e := s.Pool.WithTx(context.Background(), sql.TxOptions{}, func(tx *sql.Tx) error {
				n, v, e := s.ImportPrepared(context.Background(), tx, []PreparedImport{d})
				if e == nil && (n != 1 || v != 1) {
					t.Fatal(n, v)
				}
				return e
			})
			if (e != nil) != fail {
				t.Fatal(e)
			}
		})
	}
}

type reviewRecall func(context.Context, string, int) ([]Hit, error)

func (reviewRecall) HasContent(context.Context) (bool, error)                    { return true, nil }
func (f reviewRecall) Search(c context.Context, q string, n *int) ([]Hit, error) { return f(c, q, 1) }

func (f reviewRecall) Candidates(c context.Context, q string, n int) ([]Hit, error) {
	return f(c, q, n)
}
func reviewFinishSteps(done, failed, lost bool) []sqlStep {
	check := queryStep("SELECT j.document_id", sqlValues(contractDocA))
	if lost {
		check.rows = nil
	}
	steps := []sqlStep{terminalStep("begin"), queryStep("FOR UPDATE", sqlValues(contractDocA)), check}
	if !lost {
		steps = append(steps, execStep("UPDATE knowledge_source"))
		if done {
			steps = append(steps, execStep("DELETE FROM knowledge_review_job"))
		} else {
			steps = append(steps, execStep("UPDATE knowledge_review_job"))
		}
	}
	return append(steps, terminalStep("commit"))
}
func TestReviewWorkerClaimFinishAndRecoveryContract(t *testing.T) {
	for _, mode := range []string{"empty", "done", "partial", "recall-failed", "stale", "claim-failed", "denied"} {
		t.Run(mode, func(t *testing.T) {
			claim := queryStep("WITH ready AS", sqlValues(contractDocA, 2, 0, 1))
			if mode == "empty" {
				claim.rows = nil
			}
			if mode == "claim-failed" {
				claim.err = errors.New("claim")
			}
			steps := []sqlStep{claim}
			if mode == "denied" {
				steps = nil
			}
			active := mode == "done" || mode == "partial" || mode == "recall-failed" || mode == "stale"
			if active {
				ch := queryStep("SELECT id::text,content,metadata::text")
				if mode != "done" {
					b, _ := json.Marshal(map[string]any{"document_id": contractDocA, "chunk_index": 0})
					ch.rows = [][]driver.Value{sqlValues(contractChunkA, "待审核的事实内容", string(b))}
				}
				steps = append(steps, ch)
				if mode != "done" {
					steps = append(steps, queryStep("WHERE s.document_id IN", sourceRow(contractDocA, "ACTIVE", 2)))
				}
				revision := 2
				if mode == "stale" {
					revision = 3
				}
				steps = append(steps, queryStep("WHERE s.document_id IN", sourceRow(contractDocA, "ACTIVE", revision)))
				if mode != "stale" {
					steps = append(steps, reviewFinishSteps(mode == "done", mode == "recall-failed", false)...)
				}
			}
			s := contractStore(t, steps...)
			released := 0
			j := &ReviewJobs{Store: s, Governance: &Governance{s}, Stats: NewStats(), Acquire: func(context.Context) (func(), error) {
				if mode == "denied" {
					return nil, nil
				}
				return func() { released++ }, nil
			}, Recall: reviewRecall(func(context.Context, string, int) ([]Hit, error) {
				if mode == "recall-failed" {
					return nil, errors.New("recall unavailable")
				}
				return nil, nil
			})}
			ran, e := j.RunNext(context.Background())
			if (e != nil) != (mode == "claim-failed") || ran != active {
				t.Fatal(ran, e)
			}
			if mode != "denied" && released != 1 {
				t.Fatal("permit leaked")
			}
		})
	}
	for _, mode := range []string{"done", "failed", "partial", "lost"} {
		t.Run("finish-"+mode, func(t *testing.T) {
			s := contractStore(t, reviewFinishSteps(mode == "done", mode == "failed", mode == "lost")...)
			j := &ReviewJobs{Store: s, Governance: &Governance{s}, Stats: NewStats()}
			if e := j.Finish(context.Background(), &ReviewClaim{DocumentID: contractDocA, Token: "lease", Revision: 2}, nil, nil, mode == "done", mode == "failed"); e != nil {
				t.Fatal(e)
			}
		})
	}
}
func TestKnowledgeExportPreservesChunksAndSourceContract(t *testing.T) {
	steps := []sqlStep{terminalStep("begin"), queryStep("FROM knowledge_document ORDER BY id", documentRow(contractDocA)), queryStep("FROM knowledge_source", sourceRow(contractDocA, "ACTIVE", 2)), queryStep("SELECT content FROM", sqlValues("first"), sqlValues("second")), terminalStep("commit")}
	s := contractStore(t, steps...)
	var b bytes.Buffer
	c := ExportContributor{Store: s}
	if c.Key() != "knowledgeDocuments" {
		t.Fatal(c.Key())
	}
	e := s.Pool.WithTx(context.Background(), sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error {
		n, e := c.Write(context.Background(), tx, &dataexport.Writer{Output: &b})
		if n != 1 {
			t.Fatal(n)
		}
		return e
	})
	if e != nil || !json.Valid(b.Bytes()) || !strings.Contains(b.String(), "second") || !strings.Contains(b.String(), "资料库") {
		t.Fatal(b.String(), e)
	}
}
