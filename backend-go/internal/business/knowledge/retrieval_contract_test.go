package knowledge

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"math"
	"reflect"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
)

func contractEmbedding(t *testing.T, s *Store) {
	t.Helper()
	s.Embedding = p8BatchEmbedding(func(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		if len(r.Input) != 1 || strings.TrimSpace(r.Input[0]) == "" {
			t.Fatal(r.Input)
		}
		return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1, 0}}}}, nil
	})
}
func contractMetadata(id string, index any) string {
	b, _ := json.Marshal(map[string]any{"document_id": id, "document_title": "合成文档", "filename": "fixture.md", "chunk_index": index})
	return string(b)
}

type contractExternal func(context.Context, string, []float32, int) ([]string, []string, error)

func (f contractExternal) Search(c context.Context, q string, v []float32, n int) ([]string, []string, error) {
	return f(c, q, v, n)
}

func TestRetrieverHybridRanksOverlapAndPreservesRawSimilarity(t *testing.T) {
	vector := queryStep("1-(embedding", sqlValues(contractChunkA, "向量与关键词重合", contractMetadata(contractDocA, 0), .91), sqlValues(contractChunkB, "只有向量", contractMetadata(contractDocB, 1), .85))
	vector.check = func(a []driver.NamedValue) {
		if a[0].Value != "[1,0]" || a[2].Value != int64(60) {
			t.Fatal(a)
		}
	}
	keyword := queryStep("WITH tokens", sqlValues("kafka", contractChunkA, "向量与关键词重合", contractMetadata(contractDocA, 0), 1, 100), sqlValues("kafka", "keyword-only", "关键词孤儿", nil, 1, 100))
	s := contractStore(t, vector, keyword)
	contractEmbedding(t, s)
	s.Reader.Properties.HybridSearchEnabled = true
	s.Reader.Properties.KeywordChannelWeight = 1
	hits, e := (&Retriever{Store: s, Stats: NewStats()}).Candidates(context.Background(), "Kafka", 999)
	if e != nil || len(hits) != 3 || hits[0].DocumentID != contractDocA || hits[0].MatchedBy != "BOTH" || hits[0].Score == nil || *hits[0].Score != .91 {
		t.Fatal(hits, e)
	}
	by := map[string]Hit{}
	for _, h := range hits {
		by[h.MatchedBy] = h
	}
	if by["KEYWORD"].DocumentID != "" || by["KEYWORD"].Score != nil || by["VECTOR"].DocumentID != contractDocB {
		t.Fatal(hits)
	}
}
func TestRetrieverChineseKeywordsAndSearchDocumentDiversity(t *testing.T) {
	t.Run("han", func(t *testing.T) {
		k := queryStep("WITH tokens")
		k.check = func(a []driver.NamedValue) {
			var tokens []string
			for i := 0; i < len(a)-1; i += 2 {
				tokens = append(tokens, a[i].Value.(string))
			}
			if !reflect.DeepEqual(tokens, []string{"知识", "检索", "召回", "文档", "分块", "测试"}) {
				t.Fatal(tokens)
			}
		}
		s := contractStore(t, queryStep("1-(embedding"), k)
		contractEmbedding(t, s)
		s.Reader.Properties.HybridSearchEnabled = true
		hits, e := (&Retriever{Store: s}).Candidates(context.Background(), "知识检索召回文档分块测试更多", 5)
		if e != nil || len(hits) != 0 {
			t.Fatal(hits, e)
		}
	})
	t.Run("deduplicate-documents", func(t *testing.T) {
		s := contractStore(t, queryStep("1-(embedding", sqlValues("a1", "first", contractMetadata(contractDocA, 0), .9), sqlValues("a2", "second", contractMetadata(contractDocA, 1), .8), sqlValues("b", "other", contractMetadata(contractDocB, "2"), .7)))
		contractEmbedding(t, s)
		s.Reader.Properties.HybridSearchEnabled = false
		n := 2
		hits, e := (&Retriever{Store: s}).Search(context.Background(), "资料", &n)
		if e != nil || len(hits) != 2 || hits[0].Content != "first" || hits[1].ChunkIndex != 2 {
			t.Fatal(hits, e)
		}
	})
}
func TestRetrieverExternalHydrationAndFallback(t *testing.T) {
	for _, mode := range []string{"ready", "outbox-pending", "outbox-error", "search-error", "hydrate-error"} {
		t.Run(mode, func(t *testing.T) {
			pending := queryStep("EXISTS(SELECT 1 FROM knowledge_search_outbox)", sqlValues(mode == "outbox-pending"))
			sentinel := errors.New("search offline")
			if mode == "outbox-error" {
				pending.err = sentinel
			}
			steps := []sqlStep{pending}
			external := mode != "outbox-pending" && mode != "outbox-error"
			if external && mode != "search-error" {
				hydration := queryStep("JOIN knowledge_document", sqlValues(contractChunkB, "semantic", contractMetadata(contractDocB, 1), "[0,1]"), sqlValues(contractChunkA, "both", contractMetadata(contractDocA, 0), "[1,0]"))
				if mode == "hydrate-error" {
					hydration.err = sentinel
				}
				steps = append(steps, hydration)
			}
			if mode != "ready" {
				steps = append(steps, queryStep("1-(embedding", sqlValues(contractChunkA, "fallback", contractMetadata(contractDocA, 0), .9)))
			}
			s := contractStore(t, steps...)
			contractEmbedding(t, s)
			s.Reader.Properties.HybridSearchEnabled = false
			calls := 0
			r := &Retriever{Store: s, Stats: NewStats(), External: contractExternal(func(_ context.Context, q string, v []float32, n int) ([]string, []string, error) {
				calls++
				if q != "query" || n != 4 || !reflect.DeepEqual(v, []float32{1, 0}) {
					t.Fatal(q, v, n)
				}
				if mode == "search-error" {
					return nil, nil, sentinel
				}
				return []string{contractChunkA}, []string{contractChunkA, contractChunkB}, nil
			})}
			hits, e := r.Candidates(context.Background(), "query", 4)
			if e != nil || len(hits) == 0 {
				t.Fatal(hits, e)
			}
			if calls != map[bool]int{true: 1, false: 0}[external] {
				t.Fatal(calls)
			}
			if mode == "ready" {
				if len(hits) != 2 || hits[0].Content != "both" || hits[0].MatchedBy != "BOTH" || hits[0].Score != nil || hits[1].MatchedBy != "VECTOR" {
					t.Fatal(hits)
				}
			} else if hits[0].Content != "fallback" {
				t.Fatal(hits)
			}
		})
	}
}
func TestRetrieverEmbeddingFailuresStopBeforeSQL(t *testing.T) {
	for _, mode := range []string{"missing", "upstream", "count", "index", "dimension", "nan"} {
		t.Run(mode, func(t *testing.T) {
			s := contractStore(t)
			if mode != "missing" {
				s.Embedding = p8BatchEmbedding(func(context.Context, model.EmbeddingRequest) (model.EmbeddingResponse, error) {
					out := model.EmbeddingResponse{Results: []model.EmbeddingResult{{Index: 0, Vector: []float32{1, 0}}}}
					switch mode {
					case "upstream":
						return out, errors.New("private endpoint")
					case "count":
						out.Results = nil
					case "index":
						out.Results[0].Index = 2
					case "dimension":
						out.Results[0].Vector = []float32{1}
					case "nan":
						out.Results[0].Vector[0] = float32(math.NaN())
					}
					return out, nil
				})
			}
			hits, e := (&Retriever{Store: s}).Candidates(context.Background(), "query", 4)
			if e == nil || hits != nil || strings.Contains(e.Error(), "private") {
				t.Fatal(hits, e)
			}
			if mode == "missing" {
				expectBusiness(t, e, api.AINotConfigured, "")
			}
			if mode == "dimension" {
				expectBusiness(t, e, api.EmbeddingDimensionMismatch, "")
			}
		})
	}
}
func TestRetrieverDatabaseErrorsAndMetadataDegradation(t *testing.T) {
	fault := errors.New("read fault")
	for _, stage := range []string{"vector-query", "vector-scan", "vector-rows", "keyword-query", "keyword-scan", "keyword-rows"} {
		t.Run(stage, func(t *testing.T) {
			v := queryStep("1-(embedding")
			k := queryStep("WITH tokens")
			step := &v
			if strings.HasPrefix(stage, "keyword") {
				step = &k
			}
			switch {
			case strings.HasSuffix(stage, "query"):
				step.err = fault
			case strings.HasSuffix(stage, "scan"):
				step.rows = [][]driver.Value{sqlValues("wrong column count")}
			case strings.HasSuffix(stage, "rows"):
				step.rowErr = fault
			}
			steps := []sqlStep{v}
			if step == &k {
				steps = append(steps, k)
			}
			s := contractStore(t, steps...)
			contractEmbedding(t, s)
			s.Reader.Properties.HybridSearchEnabled = true
			if _, e := (&Retriever{Store: s}).Candidates(context.Background(), "Kafka", 4); e == nil {
				t.Fatal("storage failure swallowed")
			}
		})
	}
	s := contractStore(t, queryStep("SELECT EXISTS", sqlValues(true)), queryStep("1-(embedding", sqlValues("raw", "unattributed", "broken-json", .8)))
	contractEmbedding(t, s)
	s.Reader.Properties.HybridSearchEnabled = false
	r := &Retriever{Store: s}
	if has, e := r.HasContent(context.Background()); e != nil || !has {
		t.Fatal(has, e)
	}
	if h, e := r.Candidates(context.Background(), "text", 1); e != nil || len(h) != 1 || h[0].DocumentID != "" || h[0].ChunkIndex != -1 {
		t.Fatal(h, e)
	}
	if _, e := r.Candidates(context.Background(), "  ", 1); e == nil {
		t.Fatal("blank candidate query accepted")
	}
	if _, e := r.Search(context.Background(), "  ", nil); e == nil {
		t.Fatal("blank search accepted")
	}
}
