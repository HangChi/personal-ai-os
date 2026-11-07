package knowledge

import (
	"context"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestCrossEncoderFailureAndUsageContracts(t *testing.T) {
	hits := []Hit{{DocumentID: contractDocA, Content: "synthetic evidence"}}
	for _, mode := range []string{"budget", "network", "read", "status", "json", "missing", "incomplete", "success"} {
		t.Run(mode, func(t *testing.T) {
			encoder, err := NewCrossEncoder("https://fixture.invalid/rerank", "synthetic", "reranker", "cohere", searchBoundaryTransport(func(*http.Request) (*http.Response, error) {
				if mode == "network" {
					return nil, errors.New("synthetic transport failure")
				}
				status := 200
				if mode == "status" {
					status = 503
				}
				body := `{"results":[{"index":0,"relevance_score":0.8}],"usage":{"total_tokens":12}}`
				switch mode {
				case "json":
					body = "{"
				case "missing":
					body = `{"results":[{}]}`
				case "incomplete":
					body = `{"results":[]}`
				}
				response := &http.Response{StatusCode: status, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}
				if mode == "read" {
					response.Body = brokenRerankBody{}
				}
				return response, nil
			}))
			if err != nil {
				t.Fatal(err)
			}
			encoder.Stats = NewStats()
			budget := time.Second
			if mode == "budget" {
				budget = time.Millisecond
			}
			ranked, scores, err := encoder.Rank(context.Background(), "question", hits, budget)
			if mode == "success" {
				if err != nil || len(ranked) != 1 || scores[hitKey(hits[0])] != .8 {
					t.Fatal(ranked, scores, err)
				}
			} else if err == nil {
				t.Fatal("failure accepted")
			}
		})
	}
}

type brokenRerankBody struct{}

func (brokenRerankBody) Read([]byte) (int, error) { return 0, errors.New("synthetic read failure") }
func (brokenRerankBody) Close() error             { return nil }
