package search

import (
	"context"
	"database/sql"
	"fmt"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/database"
	"strings"
	"time"
	"unicode/utf16"
)

type taskContributor struct{ Tasks *task.Service }

func (taskContributor) ModuleName() string { return "任务" }
func (taskContributor) Kind() string       { return "task" }
func (c taskContributor) Search(ctx context.Context, k string, n int) ([]Hit, error) {
	hits, e := (task.SearchContributor{Tasks: c.Tasks}).Search(ctx, k, n)
	out := []Hit{}
	for _, h := range hits {
		out = append(out, Hit{h.Kind, h.ID, h.Title, h.Subtitle, h.MatchedField})
	}
	return out, e
}

type sqlContributor struct {
	pool         *database.Pool
	module, kind string
	query        func(context.Context, *sql.Tx, string, int) ([]Hit, error)
}

func (c sqlContributor) ModuleName() string { return c.module }
func (c sqlContributor) Kind() string       { return c.kind }
func (c sqlContributor) Search(ctx context.Context, k string, n int) (out []Hit, err error) {
	err = c.pool.WithTx(ctx, sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error { var e error; out, e = c.query(ctx, tx, k, n); return e })
	return
}
func Contributors(pool *database.Pool, tasks *task.Service, store *knowledge.Store) []Contributor {
	return []Contributor{taskContributor{tasks}, sqlContributor{pool, "对话", "session", chatQuery}, sqlContributor{pool, "记忆", "memory", memoryQuery}, sqlContributor{pool, "日程", "event", calendarQuery}, sqlContributor{pool, "知识库", "document", func(ctx context.Context, tx *sql.Tx, k string, n int) ([]Hit, error) {
		return knowledgeQuery(ctx, tx, store.VectorTable(), k, n)
	}}}
}
func hitRows(ctx context.Context, tx *sql.Tx, q string, args ...any) ([]Hit, error) {
	out := []Hit{}
	rows, e := tx.QueryContext(ctx, q, args...)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var h Hit
		if e = rows.Scan(&h.Kind, &h.ID, &h.Title, &h.Subtitle, &h.MatchedField); e != nil {
			return nil, e
		}
		out = append(out, h)
	}
	return out, rows.Err()
}
func chatQuery(ctx context.Context, tx *sql.Tx, k string, n int) ([]Hit, error) {
	hits, e := hitRows(ctx, tx, `SELECT 'session',s.conversation_id,s.title,(SELECT count(*) FROM chat_message m WHERE m.conversation_id=s.conversation_id)::text||' 条消息','标题' FROM chat_session s WHERE lower(title) LIKE $1 ESCAPE '\' ORDER BY updated_at DESC LIMIT $2`, Pattern(k), n)
	if e != nil || len(hits) >= n {
		return hits, e
	}
	more, e := hitRows(ctx, tx, `SELECT 'session',s.conversation_id,s.title,(SELECT count(*) FROM chat_message m WHERE m.conversation_id=s.conversation_id)::text||' 条消息','消息内容' FROM (SELECT DISTINCT conversation_id FROM chat_message WHERE lower(content) LIKE $1 ESCAPE '\' LIMIT $2) m JOIN chat_session s ON s.conversation_id=m.conversation_id`, Pattern(k), n*2)
	return supplement(hits, more, n), e
}
func supplement(hits, more []Hit, n int) []Hit {
	seen := map[string]bool{}
	for _, h := range hits {
		seen[h.ID] = true
	}
	for _, h := range more {
		if len(hits) >= n {
			break
		}
		if !seen[h.ID] {
			hits = append(hits, h)
			seen[h.ID] = true
		}
	}
	return hits
}
func memoryQuery(ctx context.Context, tx *sql.Tx, k string, n int) ([]Hit, error) {
	rows, e := tx.QueryContext(ctx, `SELECT id::text,content,updated_at FROM memory WHERE lower(content) LIKE $1 ESCAPE '\' ORDER BY updated_at DESC LIMIT $2`, Pattern(k), n)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Hit{}
	for rows.Next() {
		var id, content string
		var at time.Time
		if e = rows.Scan(&id, &content, &at); e != nil {
			return nil, e
		}
		u := utf16.Encode([]rune(content))
		if len(u) > 40 {
			content = string(utf16.Decode(u[:40])) + "…"
		}
		out = append(out, Hit{"memory", id, content, "最近更新 " + at.UTC().Format("2006-01-02"), "内容"})
	}
	return out, rows.Err()
}
func calendarQuery(ctx context.Context, tx *sql.Tx, k string, n int) ([]Hit, error) {
	rows, e := tx.QueryContext(ctx, `SELECT id::text,title,description,location,starts_at,recurrence_type FROM calendar_event WHERE lower(title) LIKE $1 ESCAPE '\' OR lower(coalesce(description,'')) LIKE $1 ESCAPE '\' OR lower(coalesce(location,'')) LIKE $1 ESCAPE '\' ORDER BY starts_at DESC LIMIT $2`, Pattern(k), n)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	out := []Hit{}
	for rows.Next() {
		var id, title, recurrence string
		var description, location *string
		var at time.Time
		if e = rows.Scan(&id, &title, &description, &location, &at, &recurrence); e != nil {
			return nil, e
		}
		field := "描述"
		if strings.Contains(strings.ToLower(title), strings.ToLower(k)) {
			field = "标题"
		} else if location != nil && strings.Contains(strings.ToLower(*location), strings.ToLower(k)) {
			field = "地点"
		}
		label := map[string]string{"DAILY": "每天", "WEEKLY": "每周", "MONTHLY": "每月", "WEEKDAYS": "工作日"}[recurrence]
		if label == "" {
			label = "不重复"
		}
		out = append(out, Hit{"event", id, title, at.In(time.Local).Format("2006-01-02 15:04") + " · " + label, field})
	}
	return out, rows.Err()
}
func knowledgeQuery(ctx context.Context, tx *sql.Tx, table, k string, n int) ([]Hit, error) {
	hits, e := hitRows(ctx, tx, `SELECT 'document',id::text,title,filename||' · '||chunk_count::text||' 个分块',CASE WHEN position(lower($3) in lower(title))>0 THEN '标题' ELSE '文件名' END FROM knowledge_document WHERE lower(title) LIKE $1 ESCAPE '\' OR lower(filename) LIKE $1 ESCAPE '\' ORDER BY created_at DESC LIMIT $2`, Pattern(k), n, k)
	if e != nil || len(hits) >= n {
		return hits, e
	}
	// Join UUID as text: malformed external metadata must not abort other valid documents.
	more, e := hitRows(ctx, tx, fmt.Sprintf(`SELECT 'document',d.id::text,d.title,d.filename||' · '||d.chunk_count::text||' 个分块','正文' FROM knowledge_document d WHERE EXISTS(SELECT 1 FROM %s v WHERE v.metadata->>'document_id'=d.id::text AND v.content ILIKE $1 ESCAPE '\') ORDER BY d.id LIMIT $2`, table), Pattern(k), n)
	return supplement(hits, more, n), e
}
