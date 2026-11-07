package knowledge

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

func lockedMutationStore(t *testing.T, steps ...sqlStep) *Store {
	t.Helper()
	s := contractStore(t, steps...)
	locks, err := coordination.New(s.Pool)
	if err != nil {
		t.Fatal(err)
	}
	s.Locks = locks
	t.Cleanup(func() {
		if err := locks.Close(context.Background()); err != nil {
			t.Error(err)
		}
	})
	return s
}

func TestDocumentUpdateEmbedsBeforeFencedReplacementAndReleasesLease(t *testing.T) {
	steps := acquireDocumentSteps()
	read := queryStep("FROM knowledge_document WHERE id=$1", documentRow(contractDocA))
	embedded := false
	read.check = func(a []driver.NamedValue) {
		if a[0].Value != contractDocA || embedded {
			t.Fatal("document read order", a)
		}
	}
	write := queryStep("UPDATE knowledge_document SET", documentRow(contractDocA))
	write.check = func(a []driver.NamedValue) {
		if !embedded || a[0].Value != contractDocA || a[1].Value != "replacement" || a[2].Value != "replacement.txt" || a[6].Value != int64(1) {
			t.Fatal("replacement metadata or ordering", a)
		}
	}
	steps = append(steps, read, terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), write, execStep("DELETE FROM public.vector_store"), sqlStep{op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"), terminalStep("commit"))
	steps = append(steps, releaseDocumentSteps()...)
	s := lockedMutationStore(t, steps...)
	s.Embedding = p8BatchEmbedding(func(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		embedded = true
		if len(r.Input) != 1 || !strings.Contains(r.Input[0], "替换后的索引正文") {
			t.Fatal(r)
		}
		return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1, 0}}}}, nil
	})
	got, err := s.Update(context.Background(), contractDocA, "replacement.txt", []byte("替换后的索引正文必须完整写入"))
	if err != nil || got.ID != contractDocA || got.ChunkCount != 1 {
		t.Fatal(got, err)
	}
}

func TestDocumentDeleteCountsRemovedChunksAndScopesEveryWriteToDocument(t *testing.T) {
	steps := append(acquireDocumentSteps(), terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), queryStep("FROM knowledge_document WHERE id=$1 FOR UPDATE", sqlValues(contractDocA)))
	chunks := execStep("DELETE FROM public.vector_store WHERE metadata->>'document_id'=$1")
	chunks.affected = 3
	metadata := execStep("DELETE FROM knowledge_document WHERE id=$1")
	for _, s := range []*sqlStep{&chunks, &metadata} {
		s.check = func(a []driver.NamedValue) {
			if len(a) != 1 || a[0].Value != contractDocA {
				t.Fatal("unscoped document delete", a)
			}
		}
	}
	steps = append(steps, chunks, metadata, terminalStep("commit"))
	steps = append(steps, releaseDocumentSteps()...)
	s := lockedMutationStore(t, steps...)
	count, err := s.Delete(context.Background(), contractDocA)
	if err != nil || count != 3 {
		t.Fatal(count, err)
	}
}

func TestDocumentMutationsFailClosedAtEveryStorageBoundary(t *testing.T) {
	failure := errors.New("synthetic document mutation failure")
	for _, operation := range []string{"update", "delete"} {
		steps := acquireDocumentSteps()
		if operation == "update" {
			steps = append(steps, queryStep("FROM knowledge_document WHERE id=$1", documentRow(contractDocA)))
		}
		steps = append(steps, terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)))
		if operation == "update" {
			steps = append(steps, queryStep("UPDATE knowledge_document SET", documentRow(contractDocA)), execStep("DELETE FROM public.vector_store"), sqlStep{op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"))
		} else {
			steps = append(steps, queryStep("FROM knowledge_document WHERE id=$1 FOR UPDATE", sqlValues(contractDocA)), execStep("DELETE FROM public.vector_store"), execStep("DELETE FROM knowledge_document WHERE id=$1"))
		}
		steps = append(steps, terminalStep("commit"))
		// The acquisition transaction is checked separately; each business boundary
		// below must roll back if active, then release the already acquired lease.
		for i := 4; i < len(steps); i++ {
			target := steps[i]
			t.Run(fmt.Sprintf("%s/%d-%s", operation, i, target.op), func(t *testing.T) {
				faults := append([]sqlStep{}, steps[:i+1]...)
				faults[i].err = failure
				inTx := false
				for _, s := range steps[4:i] {
					if s.op == "begin" {
						inTx = true
					}
					if s.op == "commit" {
						inTx = false
					}
				}
				if inTx && target.op != "commit" {
					faults = append(faults, terminalStep("rollback"))
				}
				faults = append(faults, releaseDocumentSteps()...)
				s := lockedMutationStore(t, faults...)
				s.Embedding = p8BatchEmbedding(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
					return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Vector: []float32{1, 0}}}}, nil
				})
				var err error
				if operation == "update" {
					_, err = s.Update(context.Background(), contractDocA, "replacement.txt", []byte("替换后的索引正文必须完整写入"))
				} else {
					_, err = s.Delete(context.Background(), contractDocA)
				}
				if !errors.Is(err, failure) {
					t.Fatal("mutation swallowed storage failure", err)
				}
			})
		}
	}
}

func TestDocumentMutationRejectsBusyMissingAndInvalidInputBeforeWriting(t *testing.T) {
	for _, mode := range []string{"busy-update", "busy-delete", "missing-update", "missing-delete", "invalid-file", "embedding-unavailable", "lost-fence"} {
		t.Run(mode, func(t *testing.T) {
			steps := acquireDocumentSteps()
			busy := strings.HasPrefix(mode, "busy")
			if busy {
				steps[2].rows = nil
			} else {
				if mode == "missing-delete" || mode == "lost-fence" {
					fence := queryStep("SELECT slot FROM runtime_lease", sqlValues(0))
					if mode == "lost-fence" {
						fence.rows = nil
					}
					steps = append(steps, terminalStep("begin"), fence)
					if mode == "missing-delete" {
						steps = append(steps, queryStep("FROM knowledge_document WHERE id=$1 FOR UPDATE"))
					}
					steps = append(steps, terminalStep("rollback"))
				} else {
					read := queryStep("FROM knowledge_document WHERE id=$1", documentRow(contractDocA))
					if mode == "missing-update" {
						read.rows = nil
					}
					steps = append(steps, read)
				}
				steps = append(steps, releaseDocumentSteps()...)
			}
			s := lockedMutationStore(t, steps...)
			var err error
			if strings.HasSuffix(mode, "delete") || mode == "lost-fence" {
				_, err = s.Delete(context.Background(), contractDocA)
			} else {
				name := "replacement.txt"
				if mode == "invalid-file" {
					name = "replacement.exe"
				}
				_, err = s.Update(context.Background(), contractDocA, name, []byte("合成正文必须足够长以形成有效分块"))
			}
			if err == nil {
				t.Fatal("unsafe mutation accepted")
			}
			if busy {
				expectBusiness(t, err, api.BusinessConflict, "正在写入")
			}
			if strings.HasPrefix(mode, "missing") {
				expectBusiness(t, err, api.ResourceNotFound, "不存在")
			}
			if mode == "lost-fence" && !errors.Is(err, coordination.ErrLeaseLost) {
				t.Fatal(err)
			}
		})
	}
}
