package knowledge

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type OpenSearch struct {
	endpoint, index, username, password string
	dimensions                          int
	http                                *http.Client
	mu                                  sync.Mutex
	initialized                         bool
}

func NewOpenSearch(endpoint, index, username, password string, dimensions int, transport http.RoundTripper) (*OpenSearch, error) {
	u, e := url.Parse(endpoint)
	if e != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || !regexp.MustCompile(`^[a-z][a-z0-9_-]{0,62}$`).MatchString(index) || dimensions < 1 {
		return nil, errors.New("OpenSearch 配置非法")
	}
	return &OpenSearch{endpoint: strings.TrimRight(endpoint, "/"), index: index, username: username, password: password, dimensions: dimensions, http: &http.Client{Transport: transport, Timeout: 5 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}
func (s *OpenSearch) request(ctx context.Context, method, path string, payload any) (int, []byte, error) {
	var body io.Reader
	if payload != nil {
		b, e := json.Marshal(payload)
		if e != nil {
			return 0, nil, e
		}
		body = bytes.NewReader(b)
	}
	r, e := http.NewRequestWithContext(ctx, method, s.endpoint+"/"+s.index+path, body)
	if e != nil {
		return 0, nil, e
	}
	r.Header.Set("Content-Type", "application/json")
	if s.username != "" {
		r.SetBasicAuth(s.username, s.password)
	}
	response, e := s.http.Do(r)
	if e != nil {
		return 0, nil, errors.New("OpenSearch 暂不可用")
	}
	defer response.Body.Close()
	b, e := io.ReadAll(io.LimitReader(response.Body, 2_000_001))
	if e != nil || len(b) > 2_000_000 {
		return 0, nil, errors.New("OpenSearch 响应无效或过大")
	}
	return response.StatusCode, b, nil
}
func (s *OpenSearch) Initialize(ctx context.Context) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.initialized {
		return nil
	}
	code, _, e := s.request(ctx, "HEAD", "", nil)
	if e != nil {
		return e
	}
	if code == 404 {
		mapping := map[string]any{"settings": map[string]any{"index": map[string]any{"knn": true, "number_of_shards": 1, "number_of_replicas": 0}}, "mappings": map[string]any{"properties": map[string]any{"content": map[string]any{"type": "text", "analyzer": "cjk"}, "embedding": map[string]any{"type": "knn_vector", "dimension": s.dimensions, "method": map[string]any{"name": "hnsw", "engine": "lucene", "space_type": "cosinesimil"}}}}}
		code, _, e = s.request(ctx, "PUT", "", mapping)
		if e != nil {
			return e
		}
		if code != 200 && code != 400 {
			return errors.New("搜索索引创建失败")
		}
	} else if code != 200 {
		return errors.New("搜索索引不可访问")
	}
	code, b, e := s.request(ctx, "GET", "/_mapping", nil)
	if e != nil {
		return e
	}
	var mappings map[string]struct {
		Mappings struct {
			Properties struct {
				Embedding struct {
					Dimension int `json:"dimension"`
				} `json:"embedding"`
			} `json:"properties"`
		} `json:"mappings"`
	}
	if code != 200 || json.Unmarshal(b, &mappings) != nil || mappings[s.index].Mappings.Properties.Embedding.Dimension != s.dimensions {
		return errors.New("搜索索引向量维度不一致，请使用新索引名重建")
	}
	s.initialized = true
	return nil
}
func (s *OpenSearch) Project(ctx context.Context, id string, revision int64, chunk *Chunk) error {
	if _, ok := api.ParseJavaUUID(id); !ok || revision < 1 {
		return errors.New("非法投影标识")
	}
	if e := s.Initialize(ctx); e != nil {
		return e
	}
	path := "/_doc/" + id + "?version=" + strconv.FormatInt(revision, 10) + "&version_type=external_gte&refresh=true"
	method := "DELETE"
	var payload any
	if chunk != nil {
		method = "PUT"
		payload = map[string]any{"content": chunk.Content, "embedding": chunk.Vector}
	}
	code, _, e := s.request(ctx, method, path, payload)
	if e != nil {
		return e
	}
	if code != 200 && code != 201 && code != 404 && code != 409 {
		return errors.New("搜索投影写入失败")
	}
	return nil
}
func (s *OpenSearch) Search(ctx context.Context, query string, vector []float32, limit int) ([]string, []string, error) {
	if e := s.Initialize(ctx); e != nil {
		return nil, nil, e
	}
	ids := func(query any) ([]string, error) {
		code, b, e := s.request(ctx, "POST", "/_search", map[string]any{"size": limit, "_source": false, "query": query})
		if e != nil {
			return nil, e
		}
		if code != 200 {
			return nil, errors.New("搜索召回失败")
		}
		var result struct {
			Hits struct {
				Hits []struct {
					ID string `json:"_id"`
				} `json:"hits"`
			} `json:"hits"`
		}
		if e = json.Unmarshal(b, &result); e != nil {
			return nil, e
		}
		out := []string{}
		for _, h := range result.Hits.Hits {
			id, ok := api.ParseJavaUUID(h.ID)
			if !ok {
				return nil, errors.New("非法搜索候选ID")
			}
			out = append(out, id)
		}
		return out, nil
	}
	lex, e := ids(map[string]any{"match": map[string]any{"content": query}})
	if e != nil {
		return nil, nil, e
	}
	vec, e := ids(map[string]any{"knn": map[string]any{"embedding": map[string]any{"vector": vector, "k": limit}}})
	return lex, vec, e
}

type Projector interface {
	Project(context.Context, string, int64, *Chunk) error
}
type Projection struct {
	Store   *Store
	Gateway Projector
	Stats   *Stats
}

func (p *Projection) Pending(ctx context.Context) (int64, error) {
	var n int64
	e := p.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT count(*) FROM knowledge_search_outbox").Scan(&n)
	})
	return n, e
}
func (p *Projection) Reindex(ctx context.Context) (int64, error) {
	var n int64
	e := p.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		r, e := c.ExecContext(ctx, "INSERT INTO knowledge_search_outbox(chunk_id) SELECT id FROM "+p.Store.table+" ON CONFLICT(chunk_id) DO UPDATE SET revision=nextval('knowledge_search_revision'),changed_at=now()")
		if e == nil {
			n, e = r.RowsAffected()
		}
		return e
	})
	return n, e
}
func (p *Projection) Sync(ctx context.Context) error {
	for i := 0; i < 20 && ctx.Err() == nil; i++ {
		worked, e := p.RunNext(ctx)
		if e != nil {
			return e
		}
		if !worked {
			return nil
		}
	}
	return ctx.Err()
}
func (p *Projection) RunNext(ctx context.Context) (bool, error) {
	token := newID()
	var id string
	var revision int64
	var attempts int
	e := p.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `WITH ready AS (SELECT chunk_id FROM knowledge_search_outbox WHERE available_at<=now() ORDER BY revision FOR UPDATE SKIP LOCKED LIMIT 1) UPDATE knowledge_search_outbox o SET claim_token=$1,available_at=now()+interval '60 seconds',attempts=attempts+1 FROM ready WHERE o.chunk_id=ready.chunk_id RETURNING o.chunk_id::text,o.revision,o.attempts`, token).Scan(&id, &revision, &attempts)
	})
	if errors.Is(e, sql.ErrNoRows) {
		return false, nil
	}
	if e != nil {
		return false, e
	}
	chunks, e := p.Store.ProjectionChunks(ctx, []string{id})
	if e == nil {
		var chunk *Chunk
		if len(chunks) > 0 {
			chunk = &chunks[0]
		}
		e = p.Gateway.Project(ctx, id, revision, chunk)
	}
	result := "success"
	if e != nil {
		result = "retry"
	}
	if p.Stats != nil {
		p.Stats.Count("search.projections", result)
	}
	finish, cancel := context.WithTimeout(context.WithoutCancel(ctx), 3*time.Second)
	defer cancel()
	writeErr := p.Store.Pool.WithConn(finish, func(c *sql.Conn) error {
		if e == nil {
			_, err := c.ExecContext(finish, "DELETE FROM knowledge_search_outbox WHERE chunk_id=$1 AND revision=$2 AND claim_token=$3 AND available_at>clock_timestamp()", id, revision, token)
			return err
		}
		_, err := c.ExecContext(finish, "UPDATE knowledge_search_outbox SET claim_token=NULL,available_at=now()+$1*interval '1 second' WHERE chunk_id=$2 AND revision=$3 AND claim_token=$4", min(300, 1<<min(9, attempts)), id, revision, token)
		return err
	})
	if writeErr != nil {
		return true, writeErr
	}
	return true, nil
}

func (s *OpenSearch) String() string { return fmt.Sprintf("OpenSearch{index:%s,redacted}", s.index) }
