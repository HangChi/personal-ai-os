package task

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

type Project struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Description *string   `json:"description"`
	Archived    bool      `json:"archived"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}
type ProjectChange struct {
	Name        *string `json:"name"`
	Description *string `json:"description"`
	Archived    *bool   `json:"archived"`
}
type RunSummary struct {
	ID             string    `json:"id"`
	ConversationID string    `json:"conversationId"`
	Title          string    `json:"title"`
	Status         string    `json:"status"`
	UpdatedAt      time.Time `json:"updatedAt"`
}
type RunPage struct {
	Items []RunSummary `json:"items"`
	Total int64        `json:"total"`
}
type workspaceStore interface {
	Projects(context.Context) ([]Project, error)
	SaveProject(context.Context, string, ProjectChange) (Project, error)
	UnfinishedRuns(context.Context) (RunPage, error)
}

func nullableID(id string) any {
	if id == "" {
		return nil
	}
	return id
}
func validateWorkspace(t Task) error {
	if t.RecurrenceType == "" {
		t.RecurrenceType = "NONE"
	}
	if t.RecurrenceTimezone == "" {
		t.RecurrenceTimezone = "UTC"
	}
	for _, id := range []*string{t.ProjectID, t.ParentTaskID} {
		if id != nil && !uuidPattern.MatchString(*id) {
			return invalid("项目或父任务标识无效")
		}
	}
	switch t.RecurrenceType {
	case "NONE", "DAILY", "WEEKDAYS", "WEEKLY", "MONTHLY":
	default:
		return invalid("周期规则无效")
	}
	if len(t.RecurrenceTimezone) > 100 {
		return invalid("周期任务时区无效")
	}
	if _, e := time.LoadLocation(t.RecurrenceTimezone); e != nil {
		return invalid("周期任务时区无效")
	}
	if t.RecurrenceType != "NONE" && (t.DueAt == nil || t.ParentTaskID != nil) {
		return invalid("周期任务必须设置截止时间，且不能同时是子任务")
	}
	return nil
}
func workspaceError(err error) error {
	var pg *pgconn.PgError
	if errors.As(err, &pg) && pg.Code == "23514" && strings.HasPrefix(pg.Message, "workspace:") {
		return api.NewBusinessError(api.ValidationFailed, strings.TrimPrefix(pg.Message, "workspace:"))
	}
	return nil
}
func (s *Service) workspace() (workspaceStore, error) {
	v, ok := s.store.(workspaceStore)
	if !ok {
		return nil, errors.New("工作台仓储不可用")
	}
	return v, nil
}
func (s *Service) Projects(ctx context.Context) ([]Project, error) {
	v, e := s.workspace()
	if e != nil {
		return nil, e
	}
	return v.Projects(ctx)
}
func (s *Service) SaveProject(ctx context.Context, id string, c ProjectChange) (Project, error) {
	if id == "" && c.Name == nil {
		return Project{}, invalid("项目名称不能为空")
	}
	if c.Name != nil {
		name := strings.TrimSpace(*c.Name)
		if name == "" || utf16Length(name) > 100 {
			return Project{}, invalid("项目名称不能为空且不能超过 100 字")
		}
		c.Name = &name
	}
	if c.Description != nil && utf16Length(*c.Description) > 2000 {
		return Project{}, invalid("项目描述不能超过 2000 字")
	}
	v, e := s.workspace()
	if e != nil {
		return Project{}, e
	}
	return v.SaveProject(ctx, id, c)
}
func (s *Service) UnfinishedRuns(ctx context.Context) (RunPage, error) {
	v, e := s.workspace()
	if e != nil {
		return RunPage{}, e
	}
	return v.UnfinishedRuns(ctx)
}
func (s *PostgresStore) Projects(ctx context.Context) (out []Project, err error) {
	out = []Project{}
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, `SELECT id::text,name,description,archived,created_at,updated_at FROM project ORDER BY created_at,id`)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var p Project
			if e = rows.Scan(&p.ID, &p.Name, &p.Description, &p.Archived, &p.CreatedAt, &p.UpdatedAt); e != nil {
				return e
			}
			out = append(out, p)
		}
		return rows.Err()
	})
	return
}
func (s *PostgresStore) SaveProject(ctx context.Context, id string, c ProjectChange) (p Project, err error) {
	err = s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		var row *sql.Row
		if id == "" {
			row = conn.QueryRowContext(ctx, `INSERT INTO project(name,description) VALUES($1,$2) RETURNING id::text,name,description,archived,created_at,updated_at`, c.Name, c.Description)
		} else {
			row = conn.QueryRowContext(ctx, `UPDATE project SET name=coalesce($2,name),description=coalesce($3,description),archived=coalesce($4,archived),updated_at=now() WHERE id=$1::uuid RETURNING id::text,name,description,archived,created_at,updated_at`, id, c.Name, c.Description, c.Archived)
		}
		return row.Scan(&p.ID, &p.Name, &p.Description, &p.Archived, &p.CreatedAt, &p.UpdatedAt)
	})
	if errors.Is(err, sql.ErrNoRows) {
		err = api.NewBusinessError(api.ResourceNotFound, "项目不存在")
	}
	return
}

const latestUnfinished = ` FROM (SELECT DISTINCT ON (r.conversation_id) r.id,r.conversation_id,s.title,r.status,r.updated_at FROM chat_run r JOIN chat_session s USING(conversation_id) ORDER BY r.conversation_id,r.created_at DESC,r.id DESC) latest WHERE status<>'COMPLETED'`

func (s *PostgresStore) UnfinishedRuns(ctx context.Context) (p RunPage, err error) {
	p.Items = []RunSummary{}
	err = s.pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		if e := tx.QueryRowContext(ctx, `SELECT count(*)`+latestUnfinished).Scan(&p.Total); e != nil {
			return e
		}
		rows, e := tx.QueryContext(ctx, `SELECT id,conversation_id,coalesce(title,'未命名会话'),status,updated_at`+latestUnfinished+` ORDER BY updated_at DESC,id DESC LIMIT 20`)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var r RunSummary
			if e = rows.Scan(&r.ID, &r.ConversationID, &r.Title, &r.Status, &r.UpdatedAt); e != nil {
				return e
			}
			p.Items = append(p.Items, r)
		}
		return rows.Err()
	})
	return
}
func (h *HTTP) workspaceHTTP(w http.ResponseWriter, r *http.Request) error {
	if r.URL.Path == "/api/v1/workspace/runs" {
		if r.Method != "GET" && r.Method != "HEAD" {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		p, e := h.Tasks.UnfinishedRuns(r.Context())
		if e != nil {
			return e
		}
		return respond(w, r, 200, p)
	}
	id := ""
	if r.URL.Path != "/api/v1/projects" {
		id = strings.TrimPrefix(r.URL.Path, "/api/v1/projects/")
		if !uuidPattern.MatchString(id) {
			return web.NotFound()
		}
	}
	if id == "" && (r.Method == "GET" || r.Method == "HEAD") {
		p, e := h.Tasks.Projects(r.Context())
		if e != nil {
			return e
		}
		return respond(w, r, 200, p)
	}
	if (id == "" && r.Method != "POST") || (id != "" && r.Method != "PATCH") {
		return web.MethodNotSupported(r.Method, []string{"GET", "POST", "PATCH"})
	}
	var change ProjectChange
	obj, e := web.DecodeJSONObject(w, r, 1<<20)
	if e != nil {
		return e
	}
	b, e := json.Marshal(obj)
	if e != nil {
		return e
	}
	if e = json.Unmarshal(b, &change); e != nil {
		return web.InvalidBody(e)
	}
	p, e := h.idempotent(r, r.Method+" "+r.URL.Path, change, func(ctx context.Context) (any, error) {
		p, e := h.Tasks.SaveProject(ctx, id, change)
		return p, taskError(e)
	})
	if e != nil {
		return e
	}
	status := 200
	if id == "" {
		status = 201
	}
	return respond(w, r, status, p)
}
