//go:build integration

package knowledge

import (
	"context"
	"encoding/json"
	"math"
	"personal-ai-os/backend-go/internal/business/model"
	"reflect"
	"testing"
	"time"
)

func TestP6LiveCorpusControlledJavaRecallAndCitationParity(t *testing.T) {
	s := fixtureStore(t)
	f := readOracle(t)
	s.Reader.Properties.HybridSearchEnabled = true
	s.Reader.Properties.SimilarityThreshold = .3
	s.Embedding = embeddingFunc(func(_ context.Context, r model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		var out model.EmbeddingResponse
		for i := range r.Input {
			v := make([]float32, 1024)
			v[0] = 1
			out.Results = append(out.Results, model.EmbeddingResult{Index: i, Vector: v})
		}
		return out, nil
	})
	latencies := []int64{}
	passed := 0
	recalledAtOne, citedSource := 0, 0
	for i, c := range f.Inputs.Recalls {
		execSQL(t, s.Pool, "DELETE FROM knowledge_document; DELETE FROM vector_store")
		for _, d := range c.Documents {
			execSQL(t, s.Pool, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count) VALUES($1,$2,$3,'text/markdown',100,100,1)`, d.ID, d.Title, d.Filename)
			vector := make([]float32, 1024)
			vector[0] = float32(d.Score)
			vector[1] = float32(math.Sqrt(1 - d.Score*d.Score))
			v, e := vectorText(vector)
			if e != nil {
				t.Fatal(e)
			}
			meta, _ := json.Marshal(map[string]any{"document_id": d.ID, "document_title": d.Title, "filename": d.Filename, "chunk_index": 0})
			execSQL(t, s.Pool, `INSERT INTO vector_store(id,content,metadata,embedding) VALUES($1,$2,$3::jsonb,$4::vector)`, d.ID, d.Content, string(meta), v)
		}
		start := time.Now()
		hits, e := (&Retriever{Store: s}).Search(context.Background(), c.Query, nil)
		latencies = append(latencies, time.Since(start).Microseconds())
		if e != nil {
			t.Fatal(c.ID, e)
		}
		ids := []string{}
		for _, h := range hits {
			ids = append(ids, h.DocumentID)
		}
		if !reflect.DeepEqual(ids, f.Expected.Recalls[i].IDs) {
			t.Fatal(c.ID, ids, f.Expected.Recalls[i].IDs)
		}
		if len(hits) > 0 && hits[0].DocumentTitle == c.ExpectedSource {
			recalledAtOne++
		}
		got := BuildCitations("[1]", hits)
		want := f.Expected.Recalls[i].Citations
		if len(got) != len(want) {
			t.Fatal(c.ID, got, want)
		}
		for j := range got {
			// 原始 oracle 只记录基础引用字段。
			want[j].MatchedBy = hits[j].MatchedBy
			want[j].Source = hits[j].Source
			if got[j].Score != nil && want[j].Score != nil && math.Abs(*got[j].Score-*want[j].Score) < .00001 {
				got[j].Score = want[j].Score
			}
		}
		if !reflect.DeepEqual(got, want) {
			t.Fatal(c.ID, got, want)
		}
		if len(got) == 1 && got[0].DocumentTitle == c.ExpectedSource && got[0].Index == 1 {
			citedSource++
		}
		passed++
	}
	if recalledAtOne != passed || citedSource != passed {
		t.Fatal("ground-truth source lost", recalledAtOne, citedSource, passed)
	}
	report, _ := json.Marshal(map[string]any{"suite": "controlled_live_corpus", "cases": passed, "java_go_recall_equal": passed, "java_go_citations_equal": passed, "controlled_source_recall_at_1": float64(recalledAtOne) / float64(passed), "controlled_citation_precision": float64(citedSource) / float64(passed), "go_postgres_latency_micros": latencies, "semantic_quality_measured": false})
	t.Log("P6_EVAL " + string(report))
}
