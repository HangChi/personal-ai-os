package knowledge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strings"
	"time"
)

type CrossEncoder struct {
	endpoint, key, model, protocol string
	http                           *http.Client
	Stats                          *Stats
}

func NewCrossEncoder(endpoint, key, model, protocol string, transport http.RoundTripper) (*CrossEncoder, error) {
	u, e := url.Parse(endpoint)
	protocol = strings.ToLower(protocol)
	if e != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || strip(model) == "" || (protocol != "cohere" && protocol != "dashscope") {
		return nil, errors.New("Reranker endpoint/model/protocol invalid")
	}
	return &CrossEncoder{endpoint: endpoint, key: key, model: model, protocol: protocol, http: &http.Client{Transport: transport, Timeout: 30 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}
func (r *CrossEncoder) Rank(ctx context.Context, q string, hits []Hit, timeout time.Duration) ([]Hit, map[string]float64, error) {
	if timeout < 100*time.Millisecond {
		return nil, nil, errors.New("Rerank budget exhausted")
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	documents := []string{}
	for _, h := range hits {
		documents = append(documents, truncate(h.Content, 2000))
	}
	payload := map[string]any{"model": r.model, "query": q, "documents": documents, "top_n": len(hits)}
	if r.protocol == "dashscope" {
		payload = map[string]any{"model": r.model, "input": map[string]any{"query": q, "documents": documents}, "parameters": map[string]any{"top_n": len(hits), "return_documents": false}}
	}
	b, e := json.Marshal(payload)
	if e != nil {
		return nil, nil, e
	}
	req, e := http.NewRequestWithContext(ctx, "POST", r.endpoint, bytes.NewReader(b))
	if e != nil {
		return nil, nil, e
	}
	req.Header.Set("Content-Type", "application/json")
	if strip(r.key) != "" {
		req.Header.Set("Authorization", "Bearer "+r.key)
	}
	response, e := r.http.Do(req)
	if e != nil {
		return nil, nil, errors.New("Reranker unavailable")
	}
	defer response.Body.Close()
	b, e = io.ReadAll(io.LimitReader(response.Body, 1_000_001))
	if e != nil || len(b) > 1_000_000 || response.StatusCode != 200 {
		return nil, nil, errors.New("Reranker invalid response")
	}
	type row struct {
		Index *int     `json:"index"`
		Score *float64 `json:"relevance_score"`
	}
	var root struct {
		Results []row `json:"results"`
		Output  struct {
			Results []row `json:"results"`
		} `json:"output"`
		Usage struct {
			TotalTokens int `json:"total_tokens"`
		} `json:"usage"`
	}
	if e = json.Unmarshal(b, &root); e != nil {
		return nil, nil, e
	}
	rows := root.Results
	if r.protocol == "dashscope" {
		rows = root.Output.Results
	}
	seen := map[int]bool{}
	ordered := []Hit{}
	scores := map[string]float64{}
	for _, row := range rows {
		if row.Index == nil || row.Score == nil {
			return nil, nil, errors.New("Missing rerank values")
		}
		i, n := *row.Index, *row.Score
		if i < 0 || i >= len(hits) || seen[i] || math.IsNaN(n) || math.IsInf(n, 0) || n < 0 || n > 1 {
			return nil, nil, errors.New("Invalid rerank result")
		}
		seen[i] = true
		ordered = append(ordered, hits[i])
		scores[hitKey(hits[i])] = n
	}
	if len(ordered) != len(hits) {
		return nil, nil, errors.New("Incomplete rerank results")
	}
	sort.SliceStable(ordered, func(i, j int) bool { return scores[hitKey(ordered[i])] > scores[hitKey(ordered[j])] })
	if r.Stats != nil {
		r.Stats.Add("rag.rerank.tokens", "total", uint64(max(0, root.Usage.TotalTokens)))
	}
	return ordered, scores, nil
}
