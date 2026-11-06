package knowledge

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
	"time"
)

type Capture struct {
	ID             string  `json:"id"`
	URL            string  `json:"url"`
	DocumentID     *string `json:"documentId"`
	CurrentVersion int     `json:"currentVersion"`
	PendingJobID   *string `json:"pendingJobId"`
	Status         string  `json:"status"`
	LastError      *string `json:"lastError"`
	LastCheckedAt  string  `json:"lastCheckedAt"`
}
type CaptureVersion struct {
	Version    int    `json:"version"`
	Title      string `json:"title"`
	Content    string `json:"content"`
	Hash       string `json:"hash"`
	Status     string `json:"status"`
	CapturedAt string `json:"capturedAt"`
}
type WebFetcher interface {
	Fetch(context.Context, string) (WebPage, error)
}
type Captures struct {
	Pool    *database.Pool
	Fetcher WebFetcher
}

const captureColumns = `SELECT c.id::text,c.canonical_url,c.document_id::text,c.current_version,c.pending_job_id::text,coalesce(j.status,CASE WHEN c.last_error IS NOT NULL THEN 'FAILED' WHEN c.document_id IS NULL THEN 'EMPTY' ELSE 'SUCCEEDED' END),coalesce(c.last_error,j.error),c.last_checked_at FROM knowledge_capture c LEFT JOIN knowledge_index_job j ON j.id=c.pending_job_id`

func scanCapture(r scanner) (Capture, error) {
	var c Capture
	var at time.Time
	e := r.Scan(&c.ID, &c.URL, &c.DocumentID, &c.CurrentVersion, &c.PendingJobID, &c.Status, &c.LastError, &at)
	c.LastCheckedAt = at.UTC().Format(time.RFC3339Nano)
	return c, e
}
func (s *Captures) Get(ctx context.Context, id string) (c Capture, e error) {
	e = s.Pool.WithConn(ctx, func(conn *sql.Conn) error {
		c, e = scanCapture(conn.QueryRowContext(ctx, captureColumns+" WHERE c.id=$1", id))
		return e
	})
	if errors.Is(e, sql.ErrNoRows) {
		e = api.NewBusinessError(api.ResourceNotFound, "采集来源不存在")
	}
	return
}
func (s *Captures) List(ctx context.Context) (any, error) {
	items := []Capture{}
	var total int
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM knowledge_capture").Scan(&total); e != nil {
			return e
		}
		rows, e := c.QueryContext(ctx, captureColumns+" ORDER BY c.created_at DESC LIMIT 100")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			v, e := scanCapture(rows)
			if e != nil {
				return e
			}
			items = append(items, v)
		}
		return rows.Err()
	})
	return map[string]any{"items": items, "total": total}, e
}
func (s *Captures) Capture(ctx context.Context, url string) (Capture, error) {
	page, e := s.Fetcher.Fetch(ctx, url)
	if e != nil {
		return Capture{}, e
	}
	var id string
	e = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		return tx.QueryRowContext(ctx, "SELECT queue_knowledge_capture($1,$2,$3,$4)::text", page.URL, page.Title, page.Content, ContentHash(page.Content)).Scan(&id)
	})
	if e != nil {
		return Capture{}, api.NewBusinessError(api.BusinessConflict, "采集提交失败，可能队列已满；请稍后重试")
	}
	return s.Get(ctx, id)
}
func (s *Captures) Refresh(ctx context.Context, id string) (Capture, error) {
	current, e := s.Get(ctx, id)
	if e != nil || current.PendingJobID != nil {
		return current, e
	}
	result, e := s.Capture(ctx, current.URL)
	if e != nil {
		var business *api.BusinessError
		message := "网页采集暂时失败，请稍后重试"
		if errors.As(e, &business) {
			message = business.Error()
		}
		_ = s.Pool.WithConn(ctx, func(c *sql.Conn) error {
			_, e := c.ExecContext(ctx, "UPDATE knowledge_capture SET last_checked_at=now(),last_error=$1 WHERE id=$2", message, id)
			return e
		})
	}
	return result, e
}
func (s *Captures) Versions(ctx context.Context, id string) (any, error) {
	if _, e := s.Get(ctx, id); e != nil {
		return nil, e
	}
	items := []CaptureVersion{}
	var total int
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM knowledge_capture_version WHERE capture_id=$1", id).Scan(&total); e != nil {
			return e
		}
		rows, e := c.QueryContext(ctx, "SELECT version,title,content,content_hash,status,captured_at FROM knowledge_capture_version WHERE capture_id=$1 ORDER BY version DESC LIMIT 10", id)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var v CaptureVersion
			var at time.Time
			if e := rows.Scan(&v.Version, &v.Title, &v.Content, &v.Hash, &v.Status, &at); e != nil {
				return e
			}
			v.CapturedAt = at.UTC().Format(time.RFC3339Nano)
			items = append(items, v)
		}
		return rows.Err()
	})
	return map[string]any{"items": items, "total": total}, e
}
func (h *HTTP) serveCaptures(w http.ResponseWriter, r *http.Request) (int, any, error) {
	s := &Captures{Pool: h.Store.Pool, Fetcher: PublicFetcher{}}
	path := strings.TrimPrefix(r.URL.Path, basePath+"/captures")
	if path == "" {
		if r.Method == "GET" || r.Method == "HEAD" {
			v, e := s.List(r.Context())
			return 200, v, e
		}
		if r.Method != "POST" {
			return 0, nil, web.MethodNotSupported(r.Method, []string{"GET", "POST"})
		}
		obj, e := web.DecodeJSONObject(w, r, 8192)
		if e != nil {
			return 0, nil, e
		}
		url, e := web.JSONStringField(obj, "url")
		if e != nil || url == nil {
			return 0, nil, invalid("请填写 URL")
		}
		v, e := s.Capture(r.Context(), *url)
		return 200, v, e
	}
	parts := strings.Split(strings.TrimPrefix(path, "/"), "/")
	if len(parts) != 2 {
		return 0, nil, web.NotFound()
	}
	id, ok := api.ParseJavaUUID(parts[0])
	if !ok {
		return 0, nil, web.InvalidParameter("id")
	}
	if parts[1] == "refresh" {
		if r.Method != "POST" {
			return 0, nil, web.MethodNotSupported(r.Method, []string{"POST"})
		}
		v, e := s.Refresh(r.Context(), id)
		return 200, v, e
	}
	if parts[1] == "versions" {
		if r.Method != "GET" && r.Method != "HEAD" {
			return 0, nil, web.MethodNotSupported(r.Method, []string{"GET"})
		}
		v, e := s.Versions(r.Context(), id)
		return 200, v, e
	}
	return 0, nil, web.NotFound()
}
