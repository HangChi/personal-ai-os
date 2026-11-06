package knowledge

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/api"
)

type Space struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}
type Metadata struct {
	SpaceID        string  `json:"spaceId"`
	Version        string  `json:"version"`
	Scope          string  `json:"scope"`
	EffectiveFrom  *string `json:"effectiveFrom"`
	EffectiveUntil *string `json:"effectiveUntil"`
	Authority      string  `json:"authority"`
	Lifecycle      string  `json:"lifecycle"`
}
type DocumentSource struct {
	DocumentID string     `json:"documentId"`
	Title      string     `json:"title"`
	Source     SourceInfo `json:"source"`
}
type Conflict struct {
	ID                          string  `json:"id"`
	LeftID                      string  `json:"leftId"`
	RightID                     string  `json:"rightId"`
	LeftChunk                   int     `json:"leftChunk"`
	RightChunk                  int     `json:"rightChunk"`
	LeftQuote                   string  `json:"leftQuote"`
	RightQuote                  string  `json:"rightQuote"`
	Reason                      string  `json:"reason"`
	Status                      string  `json:"status"`
	Resolution                  *string `json:"resolution"`
	WinnerID                    *string `json:"winnerId"`
	ResolutionReason            *string `json:"resolutionReason"`
	leftRevision, rightRevision int64
}
type Resolution struct {
	Decision string  `json:"decision"`
	WinnerID *string `json:"winnerId"`
	Reason   string  `json:"reason"`
}
type Governance struct{ Store *Store }

func invalid(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }

type queryer interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func (g *Governance) Spaces(ctx context.Context) ([]Space, error) {
	out := []Space{}
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT id::text,name FROM knowledge_space ORDER BY name")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var s Space
			if e = rows.Scan(&s.ID, &s.Name); e != nil {
				return e
			}
			out = append(out, s)
		}
		return rows.Err()
	})
	return out, e
}
func (g *Governance) CreateSpace(ctx context.Context, name string) (Space, error) {
	name = strip(name)
	if name == "" || chars(name) > 120 {
		return Space{}, invalid("知识库名称需为 1 至 120 字符")
	}
	s := Space{newID(), name}
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		_, e := c.ExecContext(ctx, "INSERT INTO knowledge_space(id,name) VALUES($1,$2)", s.ID, s.Name)
		return e
	})
	var pg *pgconn.PgError
	if errors.As(e, &pg) && pg.Code == "23505" {
		e = invalid("知识库名称已存在")
	}
	return s, e
}

const sourceSelect = `SELECT s.document_id::text,d.title,s.space_id::text,k.name,s.version,s.scope,s.effective_from::text,s.effective_until::text,s.authority,s.lifecycle,s.revision,s.review_state FROM knowledge_source s JOIN knowledge_space k ON k.id=s.space_id JOIN knowledge_document d ON d.id=s.document_id`

func readSources(ctx context.Context, q queryer, ids []string, all bool) ([]DocumentSource, error) {
	out := []DocumentSource{}
	if !all && len(ids) == 0 {
		return out, nil
	}
	sqlText := sourceSelect
	args := []any{}
	if all {
		sqlText += " ORDER BY d.updated_at DESC LIMIT 500"
	} else {
		parameters := []string{}
		for _, id := range ids {
			args = append(args, id)
			parameters = append(parameters, "$"+strconv.Itoa(len(args)))
		}
		sqlText += " WHERE s.document_id IN (" + strings.Join(parameters, ",") + ")"
	}
	rows, e := q.QueryContext(ctx, sqlText, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var d DocumentSource
		s := &d.Source
		if e = rows.Scan(&d.DocumentID, &d.Title, &s.SpaceID, &s.SpaceName, &s.Version, &s.Scope, &s.EffectiveFrom, &s.EffectiveUntil, &s.Authority, &s.Lifecycle, &s.Revision, &s.ReviewState); e != nil {
			return nil, e
		}
		out = append(out, d)
	}
	return out, rows.Err()
}
func (g *Governance) Sources(ctx context.Context) ([]DocumentSource, error) {
	var out []DocumentSource
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error { var e error; out, e = readSources(ctx, c, nil, true); return e })
	return out, e
}
func (g *Governance) sourceMap(ctx context.Context, ids []string) (map[string]SourceInfo, error) {
	out := map[string]SourceInfo{}
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := readSources(ctx, c, ids, false)
		for _, d := range rows {
			out[d.DocumentID] = d.Source
		}
		return e
	})
	return out, e
}
func (g *Governance) Enrich(ctx context.Context, hits []Hit) ([]Hit, error) {
	ids := []string{}
	for _, h := range hits {
		if h.DocumentID != "" && !contains(ids, h.DocumentID) {
			ids = append(ids, h.DocumentID)
		}
	}
	metadata, e := g.sourceMap(ctx, ids)
	if e != nil {
		return nil, e
	}
	out := []Hit{}
	for _, h := range hits {
		if s, ok := metadata[h.DocumentID]; ok && s.Lifecycle != "SUPERSEDED" {
			h.Source = &s
			out = append(out, h)
		}
	}
	return out, nil
}
func validateMetadata(m Metadata) error {
	if _, ok := api.ParseJavaUUID(m.SpaceID); !ok || (m.Lifecycle != "ACTIVE" && m.Lifecycle != "SUPERSEDED") {
		return invalid("请选择知识库与文档状态")
	}
	for _, date := range []*string{m.EffectiveFrom, m.EffectiveUntil} {
		if date != nil {
			if _, e := time.Parse("2006-01-02", *date); e != nil {
				return invalid("日期格式无效")
			}
		}
	}
	if m.EffectiveFrom != nil && m.EffectiveUntil != nil && *m.EffectiveUntil < *m.EffectiveFrom {
		return invalid("有效截止日期早于生效日期")
	}
	if chars(m.Version) > 120 || chars(m.Scope) > 500 || chars(m.Authority) > 300 {
		return invalid("字段超过长度限制")
	}
	return nil
}
func (g *Governance) Update(ctx context.Context, id string, m Metadata) (SourceInfo, error) {
	if e := validateMetadata(m); e != nil {
		return SourceInfo{}, e
	}
	var result SourceInfo
	e := g.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var exists bool
		if e := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM knowledge_space WHERE id=$1)", m.SpaceID).Scan(&exists); e != nil {
			return e
		}
		if !exists {
			return invalid("知识库不存在")
		}
		r, e := tx.ExecContext(ctx, "UPDATE knowledge_source SET space_id=$1,version=$2,scope=$3,effective_from=$4::date,effective_until=$5::date,authority=$6,lifecycle=$7 WHERE document_id=$8", m.SpaceID, strip(m.Version), strip(m.Scope), m.EffectiveFrom, m.EffectiveUntil, strip(m.Authority), m.Lifecycle, id)
		if e != nil {
			return e
		}
		n, e := r.RowsAffected()
		if e != nil {
			return e
		}
		if n == 0 {
			return missingDocument(sql.ErrNoRows)
		}
		rows, e := readSources(ctx, tx, []string{id}, false)
		if e == nil && len(rows) == 1 {
			result = rows[0].Source
		}
		return e
	})
	return result, e
}

const conflictSelect = `SELECT id::text,left_id::text,right_id::text,left_chunk,right_chunk,left_quote,right_quote,reason,status,resolution,winner_id::text,resolution_reason,left_revision,right_revision FROM knowledge_conflict`

func readConflicts(ctx context.Context, q queryer, where string) ([]Conflict, error) {
	rows, e := q.QueryContext(ctx, conflictSelect+where)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Conflict{}
	for rows.Next() {
		var c Conflict
		e = rows.Scan(&c.ID, &c.LeftID, &c.RightID, &c.LeftChunk, &c.RightChunk, &c.LeftQuote, &c.RightQuote, &c.Reason, &c.Status, &c.Resolution, &c.WinnerID, &c.ResolutionReason, &c.leftRevision, &c.rightRevision)
		if e != nil {
			return nil, e
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
func (g *Governance) Conflicts(ctx context.Context) ([]Conflict, error) {
	var out []Conflict
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var e error
		out, e = readConflicts(ctx, c, " ORDER BY created_at DESC LIMIT 200")
		return e
	})
	return out, e
}
func (g *Governance) Resolve(ctx context.Context, id string, r Resolution) error {
	if r.Decision != "PREFER_SOURCE" && r.Decision != "KEEP_BOTH" && r.Decision != "NOT_CONFLICT" {
		return invalid("处理方式无效")
	}
	if chars(r.Reason) > 1000 {
		return invalid("字段超过长度限制")
	}
	r.Reason = strip(r.Reason)
	if r.Reason == "" {
		return invalid("请填写处理依据")
	}
	return g.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var left, right, status string
		e := tx.QueryRowContext(ctx, "SELECT left_id::text,right_id::text,status FROM knowledge_conflict WHERE id=$1 FOR UPDATE", id).Scan(&left, &right, &status)
		if errors.Is(e, sql.ErrNoRows) {
			return api.NewBusinessError(api.ResourceNotFound, "冲突不存在")
		}
		if e != nil {
			return e
		}
		if status != "OPEN" {
			return invalid("资料已变化或冲突已处理，请刷新后复核")
		}
		if r.Decision == "PREFER_SOURCE" {
			if r.WinnerID == nil || (*r.WinnerID != left && *r.WinnerID != right) {
				return invalid("选定来源必须为冲突双方之一")
			}
		} else {
			r.WinnerID = nil
		}
		if _, e = tx.ExecContext(ctx, "UPDATE knowledge_conflict SET status='RESOLVED',resolution=$1,winner_id=$2,resolution_reason=$3,resolved_at=now() WHERE id=$4", r.Decision, r.WinnerID, r.Reason, id); e != nil {
			return e
		}
		b, e := json.Marshal(r)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, "INSERT INTO knowledge_conflict_audit(conflict_id,decision) VALUES($1,$2::jsonb)", id, string(b))
		return e
	})
}
func (g *Governance) Record(ctx context.Context, conflicts []Disagreement) error {
	if len(conflicts) == 0 {
		return nil
	}
	return g.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error { return g.recordTx(ctx, tx, conflicts) })
}
func lockSources(ctx context.Context, tx *sql.Tx, ids []string) error {
	sorted := append([]string{}, ids...)
	sort.Strings(sorted)
	for _, id := range sorted {
		var got string
		e := tx.QueryRowContext(ctx, "SELECT document_id::text FROM knowledge_source WHERE document_id=$1 FOR UPDATE", id).Scan(&got)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return e
		}
	}
	return nil
}
func (g *Governance) recordTx(ctx context.Context, tx *sql.Tx, conflicts []Disagreement) error {
	ids := []string{}
	for _, c := range conflicts {
		for _, h := range []Hit{c.Left, c.Right} {
			if h.Source != nil && h.DocumentID != "" && !contains(ids, h.DocumentID) {
				ids = append(ids, h.DocumentID)
			}
		}
	}
	if e := lockSources(ctx, tx, ids); e != nil {
		return e
	}
	for _, c := range conflicts {
		a, b := c.Left, c.Right
		lq, rq := c.LeftQuote, c.RightQuote
		if a.Source == nil || b.Source == nil || a.DocumentID == b.DocumentID || a.DocumentID == "" || b.DocumentID == "" || chars(lq) < 4 || chars(rq) < 4 {
			continue
		}
		if a.DocumentID > b.DocumentID {
			a, b = b, a
			lq, rq = rq, lq
		}
		valid := true
		for _, item := range []struct {
			h Hit
			q string
		}{{a, lq}, {b, rq}} {
			var revision int64
			var content string
			e := tx.QueryRowContext(ctx, "SELECT s.revision,v.content FROM knowledge_source s JOIN "+g.Store.table+" v ON v.metadata->>'document_id'=s.document_id::text WHERE s.document_id=$1 AND v.metadata->>'chunk_index'=$2 LIMIT 1", item.h.DocumentID, strconv.Itoa(item.h.ChunkIndex)).Scan(&revision, &content)
			if errors.Is(e, sql.ErrNoRows) {
				valid = false
				break
			}
			if e != nil {
				return e
			}
			if revision != item.h.Source.Revision || !strings.Contains(content, item.q) {
				valid = false
				break
			}
		}
		if !valid {
			continue
		}
		fingerprint := digest(a.DocumentID + ":" + strconv.FormatInt(a.Source.Revision, 10) + ":" + b.DocumentID + ":" + strconv.FormatInt(b.Source.Revision, 10) + ":" + lq + ":" + rq)
		_, e := tx.ExecContext(ctx, "INSERT INTO knowledge_conflict(fingerprint,left_id,right_id,left_revision,right_revision,left_chunk,right_chunk,left_quote,right_quote,reason) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) ON CONFLICT(fingerprint) DO NOTHING", fingerprint, a.DocumentID, b.DocumentID, a.Source.Revision, b.Source.Revision, a.ChunkIndex, b.ChunkIndex, lq, rq, truncate(c.Reason, 1000))
		if e != nil {
			return e
		}
	}
	return nil
}
func (g *Governance) documentChunk(ctx context.Context, id string, index int) ([]Hit, error) {
	out := []Hit{}
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var ch Chunk
		var raw string
		e := c.QueryRowContext(ctx, "SELECT id::text,content,metadata::text FROM "+g.Store.table+" WHERE metadata->>'document_id'=$1 AND metadata->>'chunk_index'=$2 LIMIT 1", id, strconv.Itoa(index)).Scan(&ch.ID, &ch.Content, &raw)
		if errors.Is(e, sql.ErrNoRows) {
			return nil
		}
		if e != nil {
			return e
		}
		if e = json.Unmarshal([]byte(raw), &ch.Metadata); e != nil {
			return e
		}
		out = append(out, fromChunk(ch, nil, "VECTOR"))
		return nil
	})
	if e != nil {
		return nil, e
	}
	return g.Enrich(ctx, out)
}
func (g *Governance) Expand(ctx context.Context, pool []Hit) ([]Hit, error) {
	out := append([]Hit{}, pool...)
	if len(pool) == 0 {
		return out, nil
	}
	conflicts, e := g.allCurrent(ctx)
	if e != nil {
		return nil, e
	}
	ids := map[string]bool{}
	seen := map[string]bool{}
	for _, h := range pool {
		ids[h.DocumentID] = true
		seen[hitKey(h)] = true
	}
	n := 0
	for _, c := range conflicts {
		if !ids[c.LeftID] && !ids[c.RightID] {
			continue
		}
		n++
		if n > 12 {
			break
		}
		for _, side := range []struct {
			id    string
			index int
		}{{c.LeftID, c.LeftChunk}, {c.RightID, c.RightChunk}} {
			key := side.id + ":" + strconv.Itoa(side.index)
			if seen[key] {
				continue
			}
			hits, e := g.documentChunk(ctx, side.id, side.index)
			if e != nil {
				return nil, e
			}
			for _, h := range hits {
				out = append(out, h)
				seen[key] = true
			}
		}
	}
	return out, nil
}
func (g *Governance) allCurrent(ctx context.Context) ([]Conflict, error) {
	var rows []Conflict
	e := g.Store.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var e error
		rows, e = readConflicts(ctx, c, " WHERE status IN ('OPEN','RESOLVED') ORDER BY created_at DESC LIMIT 1000")
		return e
	})
	return rows, e
}
func matchesConflict(c Conflict, a, b Hit, lq, rq string) bool {
	if a.Source == nil || b.Source == nil {
		return false
	}
	return c.LeftID == a.DocumentID && c.RightID == b.DocumentID && c.leftRevision == a.Source.Revision && c.rightRevision == b.Source.Revision && c.LeftQuote == lq && c.RightQuote == rq || c.LeftID == b.DocumentID && c.RightID == a.DocumentID && c.leftRevision == b.Source.Revision && c.rightRevision == a.Source.Revision && c.LeftQuote == rq && c.RightQuote == lq
}
func (g *Governance) ApplyDecisions(ctx context.Context, review Review, pool []Hit, warnings *[]string) (Review, error) {
	rows, e := g.allCurrent(ctx)
	if e != nil {
		return review, e
	}
	current := append([]Disagreement{}, review.Conflicts...)
	// Only restore known findings connected to a query-relevant chunk. Keep these
	// anchors fixed so adding one finding cannot pull in unrelated conflict chains.
	queryEvidence := map[string]bool{}
	for _, h := range review.Relevant {
		queryEvidence[hitKey(h)] = true
	}
	for _, c := range rows {
		for _, a := range pool {
			for _, b := range pool {
				if !queryEvidence[hitKey(a)] && !queryEvidence[hitKey(b)] {
					continue
				}
				if !matchesConflict(c, a, b, c.LeftQuote, c.RightQuote) || !strings.Contains(a.Content, c.LeftQuote) || !strings.Contains(b.Content, c.RightQuote) {
					continue
				}
				exists := false
				for _, d := range current {
					if matchesConflict(c, d.Left, d.Right, d.LeftQuote, d.RightQuote) {
						exists = true
						break
					}
				}
				if !exists {
					current = append(current, Disagreement{a, b, c.LeftQuote, c.RightQuote, c.Reason})
				}
			}
		}
	}
	relevant := append([]Hit{}, review.Relevant...)
	unresolved := []Disagreement{}
	add := func(h Hit) {
		for _, x := range relevant {
			if hitKey(x) == hitKey(h) {
				return
			}
		}
		relevant = append(relevant, h)
	}
	for _, d := range current {
		var decision *Conflict
		for i, c := range rows {
			if c.Status == "RESOLVED" && c.Resolution != nil && *c.Resolution != "KEEP_BOTH" && matchesConflict(c, d.Left, d.Right, d.LeftQuote, d.RightQuote) {
				decision = &rows[i]
				break
			}
		}
		if decision == nil {
			unresolved = append(unresolved, d)
			add(d.Left)
			add(d.Right)
			continue
		}
		if decision.ResolutionReason != nil {
			*warnings = append(*warnings, "Human review: "+*decision.ResolutionReason)
		}
		if *decision.Resolution == "PREFER_SOURCE" && decision.WinnerID != nil {
			loser := d.Left
			if *decision.WinnerID == d.Left.DocumentID {
				loser = d.Right
			}
			kept := []Hit{}
			for _, h := range relevant {
				if hitKey(h) != hitKey(loser) {
					kept = append(kept, h)
				}
			}
			relevant = kept
		}
	}
	return Review{relevant, unresolved, review.Clarification}, nil
}
func (g *Governance) Retry(ctx context.Context, id string) error {
	return g.Store.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "UPDATE knowledge_source SET review_state='PENDING' WHERE document_id=$1", id); e != nil {
			return e
		}
		_, e := tx.ExecContext(ctx, "INSERT INTO knowledge_review_job(document_id,revision) SELECT document_id,revision FROM knowledge_source WHERE document_id=$1 ON CONFLICT(document_id) DO UPDATE SET revision=EXCLUDED.revision,chunk_cursor=0,next_attempt_at=now(),lease_token=NULL,attempts=0,last_error=NULL", id)
		return e
	})
}
