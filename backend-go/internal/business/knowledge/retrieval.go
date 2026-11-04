package knowledge

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"math"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

type SourceInfo struct {
	SpaceID        string  `json:"spaceId"`
	SpaceName      string  `json:"spaceName"`
	Version        string  `json:"version"`
	Scope          string  `json:"scope"`
	EffectiveFrom  *string `json:"effectiveFrom"`
	EffectiveUntil *string `json:"effectiveUntil"`
	Authority      string  `json:"authority"`
	Lifecycle      string  `json:"lifecycle"`
	Revision       int64   `json:"revision"`
	ReviewState    string  `json:"reviewState"`
}
type Hit struct {
	DocumentID    string      `json:"documentId"`
	DocumentTitle string      `json:"documentTitle"`
	Filename      string      `json:"filename"`
	ChunkIndex    int         `json:"chunkIndex"`
	Content       string      `json:"content"`
	Score         *float64    `json:"score"`
	MatchedBy     string      `json:"matchedBy"`
	Source        *SourceInfo `json:"source"`
}

func (h Hit) MarshalJSON() ([]byte, error) {
	type alias Hit
	var id *string
	if h.DocumentID != "" {
		id = &h.DocumentID
	}
	return json.Marshal(struct {
		alias
		DocumentID *string `json:"documentId"`
	}{alias(h), id})
}
func hitKey(h Hit) string { return h.DocumentID + ":" + strconv.Itoa(h.ChunkIndex) }

var tokenPattern = regexp.MustCompile(`[A-Za-z0-9][A-Za-z0-9_+#.-]*`)
var letterPattern = regexp.MustCompile(`[A-Za-z]`)
var stopWords = strings.Fields("the an and or not is are was were be been to of in on at for with by from as it its this that these those what which who how why when where do does did can could should would will may might must have has had if then than so but vs vs. eg ie etc use uses using used way ways")

func ExtractKeywords(query string) []string {
	out := []string{}
	seen := map[string]bool{}
	stop := map[string]bool{}
	for _, s := range stopWords {
		stop[s] = true
	}
	for _, s := range tokenPattern.FindAllString(query, -1) {
		s = strings.ToLower(strings.TrimRight(s, "."))
		if len(s) < 2 || !letterPattern.MatchString(s) || seen[s] || stop[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
		if len(out) == 3 {
			break
		}
	}
	return out
}
func hanKeywords(query string) []string {
	var out []string
	seen := map[string]bool{}
	var run []rune
	flush := func() {
		for i := 0; i+1 < len(run) && len(out) < 6; i += 2 {
			s := string(run[i : i+2])
			if !seen[s] {
				seen[s] = true
				out = append(out, s)
			}
		}
		run = nil
	}
	for _, r := range query {
		if unicode.Is(unicode.Han, r) {
			run = append(run, r)
		} else {
			flush()
		}
	}
	flush()
	return out
}
func fromChunk(c Chunk, score *float64, matched string) Hit {
	h := Hit{ChunkIndex: -1, Content: c.Content, Score: score, MatchedBy: matched}
	if m := c.Metadata; m != nil {
		if s, ok := m["document_id"].(string); ok {
			h.DocumentID, _ = api.ParseJavaUUID(s)
		}
		h.DocumentTitle, _ = m["document_title"].(string)
		h.Filename, _ = m["filename"].(string)
		switch n := m["chunk_index"].(type) {
		case float64:
			h.ChunkIndex = int(n)
		case int:
			h.ChunkIndex = n
		case string:
			if v, e := strconv.ParseInt(n, 10, 32); e == nil {
				h.ChunkIndex = int(v)
			}
		}
	}
	return h
}
func (s *Store) HasContent(ctx context.Context) (bool, error) {
	var has bool
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM "+s.table+")").Scan(&has)
	})
	return has, e
}

type recalled struct {
	chunk           Chunk
	score           *float64
	rank            float64
	vector, keyword bool
}
type KeywordMatch struct {
	Token                     string
	Chunk                     Chunk
	MatchedCount, TotalChunks int64
}

func (s *Store) KeywordMatches(ctx context.Context, tokens []string, limit int) ([]KeywordMatch, error) {
	matches := []KeywordMatch{}
	if len(tokens) == 0 || limit < 1 {
		return matches, nil
	}
	var values []string
	var args []any
	for i, t := range tokens {
		values = append(values, "($"+strconv.Itoa(i*2+1)+"::text,$"+strconv.Itoa(i*2+2)+"::text)")
		args = append(args, t, LikePattern(t))
	}
	args = append(args, limit)
	query := "WITH tokens(token,pattern) AS (VALUES " + strings.Join(values, ",") + "), ranked AS (SELECT t.token,v.id,v.content,v.metadata,count(*) OVER(PARTITION BY t.token) AS matched_count,row_number() OVER(PARTITION BY t.token ORDER BY length(v.content),v.id) AS rank FROM " + s.table + ` v CROSS JOIN tokens t WHERE v.content ILIKE t.pattern ESCAPE '\') SELECT token,id::text,content,metadata::text,matched_count,(SELECT count(*) FROM ` + s.table + ") FROM ranked WHERE rank<=$" + strconv.Itoa(len(args)) + " ORDER BY token,rank"
	e := s.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, e := conn.QueryContext(ctx, query, args...)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var m KeywordMatch
			var raw *string
			if e = rows.Scan(&m.Token, &m.Chunk.ID, &m.Chunk.Content, &raw, &m.MatchedCount, &m.TotalChunks); e != nil {
				return e
			}
			if raw != nil {
				_ = json.Unmarshal([]byte(*raw), &m.Chunk.Metadata)
			}
			matches = append(matches, m)
		}
		return rows.Err()
	})
	return matches, e
}
func idf(total, df int64) float64 {
	if total <= 1 {
		return 1
	}
	return max(0, min(1, math.Log(float64(total)/float64(1+df))/math.Log(float64(total))))
}
func (s *Store) queryVector(ctx context.Context, query string) ([]float32, error) {
	if s.Embedding == nil {
		return nil, api.NewBusinessError(api.AINotConfigured, api.AINotConfigured.Message())
	}
	v, e := s.Embedding.Embed(ctx, model.EmbeddingRequest{Input: []string{strip(query)}})
	if e != nil {
		return nil, api.NewBusinessError(api.AIUpstreamError, "无法连接嵌入模型服务")
	}
	if len(v.Results) != 1 || v.Results[0].Index != 0 {
		return nil, errors.New("向量数量不匹配")
	}
	if len(v.Results[0].Vector) != s.Reader.Properties.EmbeddingDimensions {
		return nil, api.NewBusinessError(api.EmbeddingDimensionMismatch, api.EmbeddingDimensionMismatch.Message())
	}
	return v.Results[0].Vector, nil
}
func (s *Store) vectorRecall(ctx context.Context, query string, limit int) ([]recalled, error) {
	vector, e := s.queryVector(ctx, query)
	if e != nil {
		return nil, e
	}
	value, e := vectorText(vector)
	if e != nil {
		return nil, e
	}
	out := []recalled{}
	e = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT id::text,content,metadata::text,1-(embedding <=> $1::vector) AS score FROM "+s.table+" WHERE (embedding <=> $1::vector)<=$2 ORDER BY embedding <=> $1::vector LIMIT $3", value, 1-s.Reader.Properties.SimilarityThreshold, limit)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var c Chunk
			var raw *string
			var score float64
			if e = rows.Scan(&c.ID, &c.Content, &raw, &score); e != nil {
				return e
			}
			if raw != nil {
				_ = json.Unmarshal([]byte(*raw), &c.Metadata)
			}
			out = append(out, recalled{chunk: c, score: &score, vector: true})
		}
		return rows.Err()
	})
	return out, e
}

type ExternalSearch interface {
	Search(context.Context, string, []float32, int) ([]string, []string, error)
}
type Retriever struct {
	Store    *Store
	External ExternalSearch
	Cache    *CandidateCache
	Stats    *Stats
}

func (r *Retriever) HasContent(ctx context.Context) (bool, error) { return r.Store.HasContent(ctx) }
func (r *Retriever) externalReady(ctx context.Context) bool {
	if r.External == nil {
		return false
	}
	var pending bool
	e := r.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM knowledge_search_outbox)").Scan(&pending)
	})
	return e == nil && !pending
}
func (r *Retriever) Candidates(ctx context.Context, query string, limit int) ([]Hit, error) {
	if strip(query) == "" {
		return nil, api.NewBusinessError(api.ValidationFailed, "检索词不能为空")
	}
	limit = max(1, min(limit, 60))
	load := func(ready bool) ([]Hit, error) { return r.load(ctx, query, limit, ready, true) }
	if r.Cache != nil {
		return r.Cache.Get(ctx, query, limit, false, func() bool { return r.externalReady(ctx) }, load)
	}
	return load(r.externalReady(ctx))
}
func (r *Retriever) Search(ctx context.Context, query string, topK *int) ([]Hit, error) {
	if strip(query) == "" {
		return nil, api.NewBusinessError(api.ValidationFailed, "检索词不能为空")
	}
	limit := r.Store.Reader.Properties.NormalizeTopK(topK)
	hits, e := r.load(ctx, query, min(limit*3, 20), false, false)
	if e != nil {
		return nil, e
	}
	out := []Hit{}
	seen := map[string]bool{}
	for _, h := range hits {
		if h.DocumentID == "" {
			if h.MatchedBy == "KEYWORD" {
				out = append(out, h)
			}
			continue
		}
		if !seen[h.DocumentID] {
			out = append(out, h)
			seen[h.DocumentID] = true
		}
		if len(out) >= limit {
			break
		}
	}
	return out, e
}
func (r *Retriever) load(ctx context.Context, query string, limit int, external, han bool) ([]Hit, error) {
	p := r.Store.Reader.Properties
	if external {
		vector, e := r.Store.queryVector(ctx, query)
		if e == nil {
			lexical, semantic, e := r.External.Search(ctx, query, vector, limit)
			if e == nil {
				scores := map[string]float64{}
				lex, vec := map[string]bool{}, map[string]bool{}
				ids := []string{}
				for ch, channel := range [][]string{lexical, semantic} {
					for i, id := range channel {
						if _, ok := scores[id]; !ok {
							ids = append(ids, id)
						}
						scores[id] += 1 / float64(p.RRFK+i+1)
						if ch == 0 {
							lex[id] = true
						} else {
							vec[id] = true
						}
					}
				}
				chunks, e := r.Store.ProjectionChunks(ctx, ids)
				if e == nil {
					sort.SliceStable(chunks, func(i, j int) bool { return scores[chunks[i].ID] > scores[chunks[j].ID] })
					out := []Hit{}
					for _, c := range chunks {
						by := "VECTOR"
						if lex[c.ID] {
							by = "KEYWORD"
							if vec[c.ID] {
								by = "BOTH"
							}
						}
						out = append(out, fromChunk(c, nil, by))
					}
					return out, nil
				}
			}
		}
	}
	vectors, e := r.Store.vectorRecall(ctx, query, limit)
	if e != nil {
		return nil, e
	}
	tokens := []string{}
	if p.HybridSearchEnabled {
		tokens = ExtractKeywords(query)
		if han && len(tokens) == 0 {
			tokens = hanKeywords(query)
		}
	}
	matches, e := r.Store.KeywordMatches(ctx, tokens, limit)
	if e != nil {
		return nil, e
	}
	candidates := map[string]*recalled{}
	order := []string{}
	for i, v := range vectors {
		v.rank = 1 / float64(p.RRFK+i+1)
		v := v
		candidates[v.chunk.ID] = &v
		order = append(order, v.chunk.ID)
	}
	positions := map[string]int{}
	for _, m := range matches {
		positions[m.Token]++
		c := candidates[m.Chunk.ID]
		if c == nil {
			c = &recalled{chunk: m.Chunk}
			candidates[m.Chunk.ID] = c
			order = append(order, m.Chunk.ID)
		}
		c.keyword = true
		c.rank += p.KeywordChannelWeight * idf(m.TotalChunks, m.MatchedCount) / float64(p.RRFK+positions[m.Token])
	}
	sort.SliceStable(order, func(i, j int) bool {
		a, b := candidates[order[i]], candidates[order[j]]
		if a.rank != b.rank {
			return a.rank > b.rank
		}
		as, bs := -1., -1.
		if a.score != nil {
			as = *a.score
		}
		if b.score != nil {
			bs = *b.score
		}
		return as > bs
	})
	hits := []Hit{}
	for _, id := range order {
		c := candidates[id]
		by := "KEYWORD"
		if c.vector {
			by = "VECTOR"
			if c.keyword {
				by = "BOTH"
			}
		}
		hits = append(hits, fromChunk(c.chunk, c.score, by))
	}
	return hits, nil
}
func (s *Store) ProjectionChunks(ctx context.Context, ids []string) ([]Chunk, error) {
	out := []Chunk{}
	if len(ids) == 0 {
		return out, nil
	}
	var args []any
	var placeholders []string
	for _, id := range ids {
		if _, ok := api.ParseJavaUUID(id); !ok {
			continue
		}
		args = append(args, id)
		placeholders = append(placeholders, "$"+strconv.Itoa(len(args)))
	}
	if len(args) == 0 {
		return out, nil
	}
	e := s.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, e := conn.QueryContext(ctx, "SELECT v.id::text,v.content,v.metadata::text,v.embedding::text FROM "+s.table+" v JOIN knowledge_document d ON d.id::text=v.metadata->>'document_id' WHERE v.id IN ("+strings.Join(placeholders, ",")+") ORDER BY v.id", args...)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var c Chunk
			var metadata string
			var vector *string
			if e = rows.Scan(&c.ID, &c.Content, &metadata, &vector); e != nil {
				return e
			}
			_ = json.Unmarshal([]byte(metadata), &c.Metadata)
			if vector != nil {
				_ = json.Unmarshal([]byte(*vector), &c.Vector)
			}
			out = append(out, c)
		}
		return rows.Err()
	})
	return out, e
}
