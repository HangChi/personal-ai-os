package knowledge

import (
	"context"
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

type pipelineObservation struct {
	Status        string   `json:"status"`
	Queries       []string `json:"queries"`
	Hits          []string `json:"hits"`
	Warnings      []string `json:"warnings"`
	Clarification string   `json:"clarification"`
	Stages        []Stage  `json:"stages"`
}

func TestPipelineFixedJavaStageReplay(t *testing.T) {
	testPipelineJavaReplay(t, "testdata/java-rag-pipeline.json", false)
}
func TestPipelineCurrentJavaStageReplay(t *testing.T) {
	testPipelineJavaReplay(t, "../../../../specs/039-evidence-aware-answers/contracts/java-rag-pipeline.json", true)
}
func testPipelineJavaReplay(t *testing.T, path string, current bool) {
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Inputs []struct {
			Name     string          `json:"name"`
			Query    string          `json:"query"`
			TopK     int             `json:"topK"`
			Hits     []Hit           `json:"hits"`
			Rewrite  json.RawMessage `json:"rewrite"`
			Rerank   json.RawMessage `json:"rerank"`
			Evidence json.RawMessage `json:"evidence"`
		} `json:"inputs"`
		Expected []pipelineObservation `json:"expected"`
	}
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Inputs) != 10 || len(fixture.Expected) != len(fixture.Inputs) {
		t.Fatal("incomplete fixed Java fixture")
	}
	for i, c := range fixture.Inputs {
		t.Run(c.Name, func(t *testing.T) {
			want := fixture.Expected[i]
			// 039 明确变更：保留非冲突相关证据并记录逐项核对降级；不改冻结 Java 基线。
			if !current && (c.Name == "conflict_top_one" || c.Name == "clarify") {
				if c.Name == "conflict_top_one" {
					want.Hits = []string{}
					for _, h := range c.Hits {
						want.Hits = append(want.Hits, hitKey(h))
					}
				}
				want.Stages = append(want.Stages, Stage{Name: "answer", Status: "FALLBACK", InputCount: len(want.Hits)})
			}
			// 这三项保留已有安全差异，不放宽结构化输出或空证据保护。
			switch c.Name {
			case "irrelevant_with_clarification":
				if want.Status != "CLARIFY" || len(want.Hits) != 0 {
					t.Fatal("unexpected Java empty-evidence contract")
				}
				want.Status, want.Clarification = "INSUFFICIENT", ""
			case "partial_rewrite":
				if len(want.Queries) != 2 || want.Stages[1].Status != "FALLBACK" {
					t.Fatal("unexpected Java partially accepted rewrite")
				}
				want.Queries = want.Queries[:1]
			case "quoted_rerank":
				if want.Stages[2].Status != "OK" || want.Hits[0] != hitKey(c.Hits[2]) {
					t.Fatal("unexpected Java numeric coercion")
				}
				want.Stages[2].Status = "FALLBACK"
				want.Hits = []string{hitKey(c.Hits[0])}
				want.Warnings = []string{"重排不可用，采用 RRF 排名"}
			}
			model := structuredFunc(func(_ context.Context, rules string, _ any, _ time.Duration) (json.RawMessage, error) {
				if strings.Contains(rules, "queries") {
					return c.Rewrite, nil
				}
				if strings.Contains(rules, "order") {
					return c.Rerank, nil
				}
				return c.Evidence, nil
			})
			pipeline := &Pipeline{Recall: &recallFixture{hits: c.Hits}, Model: model,
				Review: &EvidenceReviewer{Model: model}, Enabled: true, TopK: 4}
			got, err := pipeline.Retrieve(context.Background(), c.Query, &c.TopK)
			if err != nil {
				t.Fatal(err)
			}
			keys := []string{}
			for _, hit := range got.Hits {
				keys = append(keys, hitKey(hit))
			}
			for j := range got.Stages {
				got.Stages[j].DurationMs = 0
			}
			observation := pipelineObservation{got.Status, got.Queries, keys, got.Warnings, got.Clarification, got.Stages}
			if !reflect.DeepEqual(observation, want) {
				t.Errorf("Go observation %#v; fixed Java (with explicit safety decisions) %#v", observation, want)
			}
		})
	}
}
