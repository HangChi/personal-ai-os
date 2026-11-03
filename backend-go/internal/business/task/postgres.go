package task

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

type PostgresStore struct{ pool *database.Pool }

func NewPostgresStore(pool *database.Pool) (*PostgresStore, error) {
	if pool == nil {
		return nil, errors.New("任务数据库连接池不能为空")
	}
	return &PostgresStore{pool}, nil
}

const columns = `id::text,title,description,status,priority,due_at,completed_at,previous_status,created_at,updated_at`

type scanner interface{ Scan(...any) error }

func scan(row scanner, r *Task) error {
	return row.Scan(&r.ID, &r.Title, &r.Description, &r.Status, &r.Priority, &r.DueAt, &r.CompletedAt, &r.PreviousStatus, &r.CreatedAt, &r.UpdatedAt)
}
func (s *PostgresStore) Insert(ctx context.Context, r Task) (Task, error) {
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return scan(c.QueryRowContext(ctx, `INSERT INTO task(title,description,status,priority,due_at,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+columns, r.Title, r.Description, r.Status, r.Priority, r.DueAt, r.CreatedAt, r.UpdatedAt), &r)
	})
	return r, err
}
func (s *PostgresStore) Get(ctx context.Context, id string) (r Task, found bool, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return scan(c.QueryRowContext(ctx, `SELECT `+columns+` FROM task WHERE id=$1::uuid`, id), &r)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Task{}, false, nil
	}
	return r, err == nil, err
}
func (s *PostgresStore) Update(ctx context.Context, r Task) (Task, error) {
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return scan(c.QueryRowContext(ctx, `UPDATE task SET title=$2,description=$3,status=$4,priority=$5,due_at=$6,completed_at=$7,previous_status=$8,updated_at=$9 WHERE id=$1::uuid RETURNING `+columns, r.ID, r.Title, r.Description, r.Status, r.Priority, r.DueAt, r.CompletedAt, r.PreviousStatus, r.UpdatedAt), &r)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Task{}, ErrNotFound
	}
	return r, err
}
func (s *PostgresStore) Delete(ctx context.Context, id string) (bool, error) {
	var deleted bool
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		result, err := c.ExecContext(ctx, `DELETE FROM task WHERE id=$1::uuid`, id)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		deleted = count == 1
		return err
	})
	return deleted, err
}
func (s *PostgresStore) Search(ctx context.Context, q Query) (Page, error) {
	q = q.Normalize()
	p := Page{Page: q.Page, Size: q.Size, Items: make([]Task, 0)}
	where := ` WHERE ($1::text IS NULL OR status=$1) AND ($2::text='' OR lower(title) LIKE $2 ESCAPE '\' OR lower(coalesce(description,'')) LIKE $2 ESCAPE '\') AND ($3::text='' OR EXISTS(SELECT 1 FROM task_tag tt WHERE tt.task_id=task.id AND tt.tag=$3))`
	var status any
	if q.Status != nil {
		status = *q.Status
	}
	pattern := ""
	if strings.TrimSpace(q.Keyword) != "" {
		escaped := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(strings.ToLower(strings.TrimSpace(q.Keyword)))
		pattern = "%" + escaped + "%"
	}
	args := []any{status, pattern, q.Tag}
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		if err := c.QueryRowContext(ctx, `SELECT count(*) FROM task`+where, args...).Scan(&p.Total); err != nil {
			return err
		}
		offset := int64(q.Page) * int64(q.Size)
		if offset > 2147483647 {
			return nil
		}
		rows, err := c.QueryContext(ctx, `SELECT `+columns+` FROM task`+where+` ORDER BY CASE WHEN status='DONE' THEN 1 ELSE 0 END, CASE WHEN due_at IS NULL THEN 1 ELSE 0 END,due_at ASC,created_at DESC,id ASC LIMIT $4 OFFSET $5`, append(args, q.Size, offset)...)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var r Task
			if err := scan(rows, &r); err != nil {
				return err
			}
			p.Items = append(p.Items, r)
		}
		return rows.Err()
	})
	return p, err
}
func (s *PostgresStore) Stats(ctx context.Context, now time.Time) (stats Stats, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `SELECT count(*) FILTER(WHERE status<>'DONE'),count(*) FILTER(WHERE status<>'DONE' AND due_at<$1) FROM task`, now).Scan(&stats.Pending, &stats.Overdue)
	})
	return
}
