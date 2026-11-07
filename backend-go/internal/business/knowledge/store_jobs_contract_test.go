package knowledge

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/base64"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

func documentRow(id string) []driver.Value {
	return sqlValues(id, "标题", "fixture.txt", "text/plain", 20, 20, 1, time.Date(2026, 11, 7, 8, 0, 0, 0, time.FixedZone("fixture", 8*3600)))
}
func jobRow(id, target, status string, attempts int) []driver.Value {
	return sqlValues(id, target, false, "fixture.txt", nil, status, attempts, 5, "准备文档", nil, nil)
}
func TestStoreSchemaContractRejectsWrongDimensionsAndIdentifiers(t *testing.T) {
	for _, mode := range []string{"valid", "type-mismatch", "stored-mismatch", "type-query-error", "count-error"} {
		t.Run(mode, func(t *testing.T) {
			typ := queryStep("SELECT format_type", sqlValues("vector(2)"))
			count := queryStep("SELECT count(*)", sqlValues(0))
			if mode == "type-mismatch" {
				typ.rows[0][0] = "vector(3)"
			}
			if mode == "stored-mismatch" {
				count.rows[0][0] = int64(1)
			}
			if mode == "type-query-error" {
				typ.err = errors.New("schema query")
			}
			if mode == "count-error" {
				count.err = errors.New("count query")
			}
			steps := []sqlStep{typ}
			if mode != "type-mismatch" && mode != "type-query-error" {
				steps = append(steps, count)
			}
			s := contractStore(t, steps...)
			got, e := NewStore(context.Background(), s.Pool, s.Reader, nil, &coordination.Coordinator{}, "public", "vector_store")
			if (e == nil) != (mode == "valid") {
				t.Fatal(got, e)
			}
			if mode == "valid" && got.VectorTable() != "public.vector_store" {
				t.Fatal(got.VectorTable())
			}
		})
	}
	s := contractStore(t)
	for _, name := range []string{"public;DROP TABLE x", "a.b", ""} {
		if _, e := NewStore(context.Background(), s.Pool, s.Reader, nil, &coordination.Coordinator{}, name, "vector_store"); e == nil {
			t.Fatal(name)
		}
	}
}
func TestStoreReadsPagesAndMissingDocumentMapping(t *testing.T) {
	count := queryStep("SELECT count(*) FROM knowledge_document", sqlValues(2))
	count.check = func(a []driver.NamedValue) {
		if a[0].Value != LikePattern("A_%") {
			t.Fatal(a)
		}
	}
	page := queryStep("LIMIT $2 OFFSET $3", documentRow(contractDocA))
	page.check = func(a []driver.NamedValue) {
		if a[1].Value != int64(100) || a[2].Value != int64(100) {
			t.Fatal(a)
		}
	}
	s := contractStore(t, queryStep("WHERE id=$1", documentRow(contractDocA)), queryStep("ORDER BY created_at DESC", documentRow(contractDocA), documentRow(contractDocB)), count, page, queryStep("SELECT count(*) FROM knowledge_document", sqlValues(2)), queryStep("WHERE id=$1"))
	d, e := s.Document(context.Background(), contractDocA)
	if e != nil || d.ID != contractDocA || d.CreatedAt.Location() != time.UTC {
		t.Fatal(d, e)
	}
	list, e := s.List(context.Background())
	if e != nil || len(list) != 2 {
		t.Fatal(list, e)
	}
	p, e := s.SearchDocuments(context.Background(), " A_% ", 1, 999)
	b, _ := json.Marshal(p)
	if e != nil || !strings.Contains(string(b), contractDocA) {
		t.Fatal(string(b), e)
	}
	p, e = s.SearchDocuments(context.Background(), "x", 1<<30, 100)
	if e != nil {
		t.Fatal(p, e)
	}
	_, e = s.Document(context.Background(), contractDocA)
	expectBusiness(t, e, api.ResourceNotFound, "不存在")
}
func TestStoreIngestPreparesBeforeTransactionAndRollsBackEveryWriteFault(t *testing.T) {
	for _, phase := range []string{"success", "begin", "document", "prepare", "chunk", "commit"} {
		t.Run(phase, func(t *testing.T) {
			failure := errors.New("injected ingest failure")
			row := documentRow(contractDocA)
			doc := queryStep("INSERT INTO knowledge_document", row)
			var generated string
			doc.check = func(a []driver.NamedValue) {
				generated = a[0].Value.(string)
				row[0] = generated
				if a[6].Value != int64(1) {
					t.Fatal(a)
				}
			}
			steps := []sqlStep{terminalStep("begin"), doc, {op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"), terminalStep("commit")}
			pos := map[string]int{"begin": 0, "document": 1, "prepare": 2, "chunk": 3, "commit": 4}
			if phase != "success" {
				i := pos[phase]
				steps = steps[:i+1]
				steps[i].err = failure
				if i > 0 && phase != "commit" {
					steps = append(steps, terminalStep("rollback"))
				}
			}
			s := contractStore(t, steps...)
			calls := 0
			s.Embedding = p8BatchEmbedding(func(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
				calls++
				if len(r.Input) != 1 {
					t.Fatal(r)
				}
				return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1, 0}}}}, nil
			})
			d, e := s.Ingest(context.Background(), "fixture.txt", "标题", []byte("索引事务必须完整保存全部文档及向量"))
			if calls != 1 {
				t.Fatal(calls)
			}
			if phase == "success" {
				if e != nil || d.ID != generated || generated == "" || d.ChunkCount != 1 {
					t.Fatal(d, e)
				}
			} else if !errors.Is(e, failure) {
				t.Fatal(e)
			}
		})
	}
	s := contractStore(t)
	if _, e := s.Ingest(context.Background(), "file.exe", "", []byte("rejected")); e == nil {
		t.Fatal("invalid upload opened transaction")
	}
	if _, e := s.Ingest(context.Background(), "fixture.txt", "", []byte("模型未配置时不得写入任何文档记录")); e == nil {
		t.Fatal("missing embedding accepted")
	}
}
func TestStoreReplacementDeletesOldChunksOnlyInsideWriteTransaction(t *testing.T) {
	s := contractStore(t, terminalStep("begin"), queryStep("UPDATE knowledge_document", documentRow(contractDocA)), execStep("DELETE FROM public.vector_store"), sqlStep{op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"), terminalStep("commit"))
	e := s.Pool.WithTx(context.Background(), sql.TxOptions{}, func(tx *sql.Tx) error {
		d, e := s.persist(context.Background(), tx, contractDocA, true, Upload{Title: "new"}, []Chunk{{ID: contractChunkA, Content: "new content", Metadata: map[string]any{"document_id": contractDocA}, Vector: []float32{1, 0}}})
		if e == nil && d.ID != contractDocA {
			t.Fatal(d)
		}
		return e
	})
	if e != nil {
		t.Fatal(e)
	}
}
func TestIndexJobSubmitIdempotencyCapacityAndReplacement(t *testing.T) {
	payload := []byte("合成知识索引任务内容")
	title := "资料"
	for _, mode := range []string{"new", "replace", "replay", "conflict", "full", "target-missing"} {
		t.Run(mode, func(t *testing.T) {
			target := ""
			if mode == "replace" || mode == "target-missing" {
				target = contractDocB
			}
			hash, _ := idempotency.Digest([]any{target, "fixture.txt", title, digest(base64.StdEncoding.EncodeToString(payload))})
			previous := queryStep("SELECT digest")
			if mode == "replay" {
				previous.rows = [][]driver.Value{sqlValues(hash)}
			}
			if mode == "conflict" {
				previous.rows = [][]driver.Value{sqlValues("other-digest")}
			}
			steps := []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), previous}
			switch mode {
			case "replay":
				steps = append(steps, queryStep("SELECT "+jobColumns, jobRow(contractDocA, contractDocA, "SUCCEEDED", 1)))
			case "conflict":
			default:
				n := 0
				if mode == "full" {
					n = 20
				}
				steps = append(steps, queryStep("SELECT count(*) FROM knowledge_index_job", sqlValues(n)))
				if target != "" {
					steps = append(steps, queryStep("EXISTS(SELECT 1 FROM knowledge_document", sqlValues(mode != "target-missing")))
				}
				if mode != "full" && mode != "target-missing" {
					doc := contractDocA
					if target != "" {
						doc = target
					}
					insert := queryStep("INSERT INTO knowledge_index_job", jobRow(contractDocA, doc, "PENDING", 0))
					insert.check = func(a []driver.NamedValue) {
						if a[2].Value != (target != "") || a[6].Value != hash || string(a[5].Value.([]byte)) != string(payload) {
							t.Fatal(a)
						}
					}
					steps = append(steps, insert)
				}
			}
			fails := mode == "conflict" || mode == "full" || mode == "target-missing"
			if fails {
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, terminalStep("commit"))
			}
			job, e := (&Jobs{Store: contractStore(t, steps...)}).Submit(context.Background(), contractDocA, target, "fixture.txt", &title, payload)
			if (e != nil) != fails {
				t.Fatal(job, e)
			}
			if !fails && job.ID != contractDocA {
				t.Fatal(job)
			}
			if mode == "full" {
				expectBusiness(t, e, api.RateLimited, "队列")
			}
			if mode == "conflict" {
				expectBusiness(t, e, api.IdempotencyKeyConflict, "")
			}
		})
	}
	j := &Jobs{Store: contractStore(t)}
	for _, input := range []struct {
		file  string
		data  []byte
		title *string
	}{{"x.txt", nil, nil}, {strings.Repeat("字", 301), payload, nil}, {"x.txt", payload, func() *string { x := strings.Repeat("字", 301); return &x }()}} {
		if _, e := j.Submit(context.Background(), "", "", input.file, input.title, input.data); e == nil {
			t.Fatal(input.file)
		}
	}
}
func claimSteps(claim bool) []sqlStep {
	steps := []sqlStep{terminalStep("begin"), execStep("attempts>=3"), queryStep("WITH candidate")}
	if claim {
		steps[2].rows = [][]driver.Value{sqlValues(contractDocA, []byte("索引工作需要解析完整文档并计算嵌入"))}
		steps = append(steps, queryStep("SELECT "+jobColumns, jobRow(contractDocA, contractDocA, "RUNNING", 1)))
	}
	return append(steps, terminalStep("commit"))
}
func acquireDocumentSteps() []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("INSERT INTO runtime_lease"), queryStep("WITH ready", sqlValues(0)), terminalStep("commit")}
}
func releaseDocumentSteps() []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("UPDATE runtime_lease SET owner=null"), terminalStep("commit")}
}
func TestIndexJobClaimRenewReadAndRetrySanitization(t *testing.T) {
	steps := append(claimSteps(true), queryStep("SELECT "+jobColumns, jobRow(contractDocA, contractDocA, "RUNNING", 1)), queryStep("ORDER BY created_at DESC LIMIT 20", jobRow(contractDocA, contractDocA, "RUNNING", 1)), execStep("SET lease_until"))
	lost := execStep("SET lease_until")
	lost.affected = 0
	steps = append(steps, lost)
	retry := execStep("SET status=$3,stage=$4,error=$5")
	retry.check = func(a []driver.NamedValue) {
		if a[2].Value != "PENDING" || a[5].Value != true || strings.Contains(a[4].Value.(string), "private") {
			t.Fatal(a)
		}
	}
	fatal := execStep("SET status=$3,stage=$4,error=$5")
	fatal.check = func(a []driver.NamedValue) {
		if a[2].Value != "FAILED" || a[5].Value != false {
			t.Fatal(a)
		}
	}
	steps = append(steps, retry, fatal, execStep("DELETE FROM knowledge_index_job"), queryStep("SELECT "+jobColumns))
	j := &Jobs{Store: contractStore(t, steps...), Stats: NewStats()}
	ctx := context.Background()
	c, e := j.Claim(ctx)
	if e != nil || c == nil || len(c.Payload) == 0 || c.Token == "" {
		t.Fatal(c, e)
	}
	if v, e := j.Get(ctx, contractDocA); e != nil || v.Status != "RUNNING" {
		t.Fatal(v, e)
	}
	if v, e := j.Recent(ctx); e != nil || len(v) != 1 {
		t.Fatal(v, e)
	}
	if ok, e := j.Renew(ctx, c); e != nil || !ok {
		t.Fatal(ok, e)
	}
	if ok, e := j.Renew(ctx, c); e != nil || ok {
		t.Fatal(ok, e)
	}
	if e = j.fail(ctx, c, errors.New("private endpoint failure")); e != nil {
		t.Fatal(e)
	}
	if e = j.fail(ctx, c, invalid("invalid source")); e != nil {
		t.Fatal(e)
	}
	if e = j.Cleanup(ctx); e != nil {
		t.Fatal(e)
	}
	_, e = j.Get(ctx, "missing")
	expectBusiness(t, e, api.ResourceNotFound, "后台任务")
	counts, _ := j.Stats.Snapshot()
	if counts["jobs.attempts|retry"] != 1 || counts["jobs.attempts|failed"] != 1 {
		t.Fatal(counts)
	}
}
func TestIndexWorkerSuccessAndPreparationFailureReleaseCapacityAndLease(t *testing.T) {
	for _, mode := range []string{"success", "embedding-failed", "job-lease-lost"} {
		t.Run(mode, func(t *testing.T) {
			steps := append(claimSteps(true), acquireDocumentSteps()...)
			steps = append(steps, execStep("SET progress=$3"), execStep("SET progress=$3"))
			if mode != "embedding-failed" {
				owned := queryStep("SELECT id::text FROM knowledge_index_job", sqlValues(contractDocA))
				if mode == "job-lease-lost" {
					owned.rows = nil
				}
				steps = append(steps, execStep("SET progress=$3"), terminalStep("begin"), owned)
				if mode == "success" {
					steps = append(steps, queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), queryStep("INSERT INTO knowledge_document", documentRow(contractDocA)), sqlStep{op: "prepare", match: "INSERT INTO public.vector_store"}, execStep("INSERT INTO public.vector_store"), execStep("SET status='SUCCEEDED'"), terminalStep("commit"))
				} else {
					steps = append(steps, terminalStep("rollback"))
				}
			}
			steps = append(steps, releaseDocumentSteps()...)
			if mode == "embedding-failed" {
				steps = append(steps, execStep("SET status=$3,stage=$4,error=$5"))
			}
			s := contractStore(t, steps...)
			locks, e := coordination.New(s.Pool)
			if e != nil {
				t.Fatal(e)
			}
			t.Cleanup(func() {
				if e := locks.Close(context.Background()); e != nil {
					t.Error(e)
				}
			})
			s.Locks = locks
			contractEmbedding(t, s)
			if mode == "embedding-failed" {
				s.Embedding = p8BatchEmbedding(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
					return model.EmbeddingResponse{}, errors.New("private upstream")
				})
			}
			released := 0
			j := &Jobs{Store: s, Stats: NewStats(), Acquire: func(context.Context) (func(), error) { return func() { released++ }, nil }}
			ran, e := j.RunNext(context.Background())
			if e != nil || !ran || released != 1 || j.Stats.Active.Load() != 0 {
				t.Fatal(ran, e, released)
			}
			counts, timers := j.Stats.Snapshot()
			label := map[string]string{"success": "success", "embedding-failed": "retry", "job-lease-lost": "lease_lost"}[mode]
			if counts["jobs.attempts|"+label] != 1 || timers["jobs.duration|"].Count != 1 {
				t.Fatal(counts, timers)
			}
		})
	}
	j := &Jobs{Store: contractStore(t, claimSteps(false)...)}
	if ran, e := j.RunNext(context.Background()); e != nil || ran {
		t.Fatal(ran, e)
	}
	j = &Jobs{Store: contractStore(t), Acquire: func(context.Context) (func(), error) { return nil, nil }}
	if ran, e := j.RunNext(context.Background()); e != nil || ran {
		t.Fatal(ran, e)
	}
}
