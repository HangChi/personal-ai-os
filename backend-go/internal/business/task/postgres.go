package task

import (
	"context"
	"database/sql"
	"errors"
	"strconv"
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

const columns = `id::text,title,description,status,priority,due_at,completed_at,previous_status,created_at,updated_at,project_id::text,parent_task_id::text,recurrence_type,recurrence_timezone,recurrence_anchor_day,recurrence_generated`

type scanner interface{ Scan(...any) error }

func scan(row scanner, r *Task) error {
	return row.Scan(&r.ID, &r.Title, &r.Description, &r.Status, &r.Priority, &r.DueAt, &r.CompletedAt, &r.PreviousStatus, &r.CreatedAt, &r.UpdatedAt, &r.ProjectID, &r.ParentTaskID, &r.RecurrenceType, &r.RecurrenceTimezone, &r.RecurrenceAnchorDay, &r.RecurrenceGenerated)
}
func (s *PostgresStore) Insert(ctx context.Context, r Task) (Task, error) {
	tags := r.Tags
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if err := scan(tx.QueryRowContext(ctx, `INSERT INTO task(title,description,status,priority,due_at,created_at,updated_at,project_id,parent_task_id,recurrence_type,recurrence_timezone) VALUES($1,$2,$3,$4,$5,$6,$7,$8::uuid,$9::uuid,$10,$11) RETURNING `+columns, r.Title, r.Description, r.Status, r.Priority, r.DueAt, r.CreatedAt, r.UpdatedAt, r.ProjectID, r.ParentTaskID, r.RecurrenceType, r.RecurrenceTimezone), &r); err != nil {
			return err
		}
		r.Tags = tags
		return writeTags(ctx, tx, r.ID, tags)
	})
	return r, err
}
func (s *PostgresStore) Get(ctx context.Context, id string) (r Task, found bool, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		if err := scan(c.QueryRowContext(ctx, `SELECT `+columns+` FROM task WHERE id=$1::uuid`, id), &r); err != nil {
			return err
		}
		return readTags(ctx, c, &r)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Task{}, false, nil
	}
	return r, err == nil, err
}
func (s *PostgresStore) Update(ctx context.Context, r Task) (Task, error) {
	tags := r.Tags
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if err := scan(tx.QueryRowContext(ctx, `UPDATE task SET title=$2,description=$3,status=$4,priority=$5,due_at=$6,completed_at=$7,previous_status=$8,updated_at=$9,project_id=$10::uuid,parent_task_id=$11::uuid,recurrence_type=$12,recurrence_timezone=$13 WHERE id=$1::uuid RETURNING `+columns, r.ID, r.Title, r.Description, r.Status, r.Priority, r.DueAt, r.CompletedAt, r.PreviousStatus, r.UpdatedAt, r.ProjectID, r.ParentTaskID, r.RecurrenceType, r.RecurrenceTimezone), &r); err != nil {
			return err
		}
		r.Tags = tags
		if err := writeTags(ctx, tx, r.ID, tags); err != nil {
			return err
		}
		return syncPendingReminders(ctx, tx, r.ID)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Task{}, ErrNotFound
	}
	return r, err
}
func (s *PostgresStore) Delete(ctx context.Context, id string) (bool, error) {
	var deleted bool
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		result, err := tx.ExecContext(ctx, `DELETE FROM task WHERE id=$1::uuid`, id)
		if err != nil {
			return err
		}
		count, err := result.RowsAffected()
		deleted = count == 1
		if err != nil {
			return err
		}
		if !deleted {
			return nil
		}
		return syncPendingReminders(ctx, tx, id)
	})
	return deleted, err
}
func (s *PostgresStore) Search(ctx context.Context, q Query) (Page, error) {
	q = q.Normalize()
	p := Page{Page: q.Page, Size: q.Size, Items: make([]Task, 0)}
	where := ` WHERE ($1::text IS NULL OR status=$1) AND ($2::text='' OR lower(title) LIKE $2 ESCAPE '\' OR lower(coalesce(description,'')) LIKE $2 ESCAPE '\') AND ($3::text='' OR EXISTS(SELECT 1 FROM task_tag tt WHERE tt.task_id=task.id AND tt.tag=$3)) AND ($4::uuid IS NULL OR project_id=$4::uuid) AND ($5::uuid IS NULL OR parent_task_id=$5::uuid) AND (NOT $6::boolean OR status<>'DONE') AND ($7::timestamptz IS NULL OR due_at>=$7) AND ($8::timestamptz IS NULL OR due_at<$8)`
	var status any
	if q.Status != nil {
		status = *q.Status
	}
	pattern := ""
	if strings.TrimSpace(q.Keyword) != "" {
		escaped := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(strings.ToLower(strings.TrimSpace(q.Keyword)))
		pattern = "%" + escaped + "%"
	}
	args := []any{status, pattern, q.Tag, nullableID(q.ProjectID), nullableID(q.ParentTaskID), q.Incomplete, q.DueFrom, q.DueBefore}
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		if err := c.QueryRowContext(ctx, `SELECT count(*) FROM task`+where, args...).Scan(&p.Total); err != nil {
			return err
		}
		offset := int64(q.Page) * int64(q.Size)
		if offset > 2147483647 {
			return nil
		}
		rows, err := c.QueryContext(ctx, `SELECT `+columns+` FROM task`+where+` ORDER BY CASE WHEN status='DONE' THEN 1 ELSE 0 END, CASE WHEN due_at IS NULL THEN 1 ELSE 0 END,due_at ASC,created_at DESC,id ASC LIMIT $9 OFFSET $10`, append(args, q.Size, offset)...)
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
		if err := rows.Err(); err != nil {
			return err
		}
		if err := rows.Close(); err != nil {
			return err
		}
		return readTagsBatch(ctx, c, p.Items)
	})
	return p, err
}

type tagReader interface {
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}

func readTags(ctx context.Context, q tagReader, r *Task) error {
	rows, err := q.QueryContext(ctx, `SELECT tag FROM task_tag WHERE task_id=$1::uuid ORDER BY tag_index`, r.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	r.Tags = make([]string, 0)
	for rows.Next() {
		var tag string
		if err := rows.Scan(&tag); err != nil {
			return err
		}
		r.Tags = append(r.Tags, tag)
	}
	return rows.Err()
}

func readTagsBatch(ctx context.Context, q tagReader, tasks []Task) error {
	// Bound parameter counts for full-list/export callers while keeping a page to one query.
	const batchSize = 1000
	for offset := 0; offset < len(tasks); offset += batchSize {
		batch := tasks[offset:min(offset+batchSize, len(tasks))]
		indexes := make(map[string]int, len(batch))
		args := make([]any, len(batch))
		parameters := make([]string, len(batch))
		for i := range batch {
			batch[i].Tags = []string{}
			indexes[batch[i].ID] = i
			args[i] = batch[i].ID
			parameters[i] = "$" + strconv.Itoa(i+1) + "::uuid"
		}
		rows, err := q.QueryContext(ctx, `SELECT task_id::text,tag FROM task_tag WHERE task_id IN (`+strings.Join(parameters, ",")+`) ORDER BY task_id,tag_index`, args...)
		if err != nil {
			return err
		}
		for rows.Next() {
			var id, tag string
			if err := rows.Scan(&id, &tag); err != nil {
				rows.Close()
				return err
			}
			if index, ok := indexes[id]; ok {
				batch[index].Tags = append(batch[index].Tags, tag)
			}
		}
		err = rows.Err()
		closeErr := rows.Close()
		if err != nil {
			return err
		}
		if closeErr != nil {
			return closeErr
		}
	}
	return nil
}
func writeTags(ctx context.Context, tx *sql.Tx, id string, tags []string) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM task_tag WHERE task_id=$1::uuid`, id); err != nil {
		return err
	}
	for i, tag := range tags {
		if _, err := tx.ExecContext(ctx, `INSERT INTO task_tag(task_id,tag_index,tag) VALUES($1::uuid,$2,$3)`, id, i, tag); err != nil {
			return err
		}
	}
	return nil
}
func (s *PostgresStore) Stats(ctx context.Context, now time.Time) (stats Stats, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `SELECT count(*) FILTER(WHERE status<>'DONE'),count(*) FILTER(WHERE status<>'DONE' AND due_at<$1) FROM task`, now).Scan(&stats.Pending, &stats.Overdue)
	})
	return
}
func (s *PostgresStore) ListTags(ctx context.Context) (tags []string, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, `SELECT DISTINCT tag FROM task_tag ORDER BY tag`)
		if e != nil {
			return e
		}
		defer rows.Close()
		tags = make([]string, 0)
		for rows.Next() {
			var tag string
			if e := rows.Scan(&tag); e != nil {
				return e
			}
			tags = append(tags, tag)
		}
		return rows.Err()
	})
	return
}
func (s *PostgresStore) DeleteAll(ctx context.Context, ids []string) (count int, err error) {
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		for _, id := range ids {
			result, e := tx.ExecContext(ctx, `DELETE FROM task WHERE id=$1::uuid`, id)
			if e != nil {
				return e
			}
			n, e := result.RowsAffected()
			if e != nil {
				return e
			}
			count += int(n)
			if n > 0 {
				if e := syncPendingReminders(ctx, tx, id); e != nil {
					return e
				}
			}
		}
		return nil
	})
	return
}
func syncPendingReminders(ctx context.Context, tx *sql.Tx, id string) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM reminder r WHERE r.source_type='TASK' AND r.source_id=$1::uuid AND r.status='PENDING' AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id=r.source_id AND t.status<>'DONE' AND t.due_at=r.occurrence_at)`, id); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `UPDATE reminder r SET title=t.title FROM task t WHERE r.source_type='TASK' AND r.source_id=$1::uuid AND r.status='PENDING' AND t.id=r.source_id AND r.title IS DISTINCT FROM t.title`, id)
	return err
}
func (s *PostgresStore) ExportBatch(ctx context.Context, page, size int) ([]Task, error) {
	return s.queryTasks(ctx, `SELECT `+columns+` FROM task ORDER BY id LIMIT $1 OFFSET $2`, size, int64(page)*int64(size))
}
func (s *PostgresStore) SearchHits(ctx context.Context, keyword string, limit int) ([]Task, error) {
	pattern := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(strings.ToLower(strings.TrimSpace(keyword))) + "%"
	return s.queryTasks(ctx, `SELECT `+columns+` FROM task WHERE lower(title) LIKE $1 ESCAPE '\' OR lower(coalesce(description,'')) LIKE $1 ESCAPE '\' ORDER BY updated_at DESC,id ASC LIMIT $2`, pattern, limit)
}
func (s *PostgresStore) queryTasks(ctx context.Context, query string, args ...any) (items []Task, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, query, args...)
		if e != nil {
			return e
		}
		items = make([]Task, 0)
		for rows.Next() {
			var task Task
			if e = scan(rows, &task); e != nil {
				rows.Close()
				return e
			}
			items = append(items, task)
		}
		if e = rows.Err(); e != nil {
			rows.Close()
			return e
		}
		if e = rows.Close(); e != nil {
			return e
		}
		return readTagsBatch(ctx, c, items)
	})
	return
}

const displayOrder = ` ORDER BY CASE WHEN status='DONE' THEN 1 ELSE 0 END, CASE WHEN due_at IS NULL THEN 1 ELSE 0 END,due_at ASC,created_at DESC,id ASC`

func (s *PostgresStore) DueBetween(ctx context.Context, from, to time.Time) ([]Task, error) {
	return s.queryTasks(ctx, `SELECT `+columns+` FROM task WHERE due_at BETWEEN $1 AND $2 AND status<>'DONE' ORDER BY due_at ASC,id ASC`, from, to)
}
func (s *PostgresStore) ListAll(ctx context.Context) ([]Task, error) {
	return s.queryTasks(ctx, `SELECT `+columns+` FROM task`+displayOrder)
}
func (s *PostgresStore) FindByIDs(ctx context.Context, ids []string) ([]Task, error) {
	if len(ids) == 0 {
		return []Task{}, nil
	}
	args := make([]any, len(ids))
	placeholders := make([]string, len(ids))
	for i, id := range ids {
		args[i] = id
		placeholders[i] = "$" + strconv.Itoa(i+1) + "::uuid"
	}
	return s.queryTasks(ctx, `SELECT `+columns+` FROM task WHERE id IN (`+strings.Join(placeholders, ",")+`)`+displayOrder, args...)
}
func (s *PostgresStore) CountAll(ctx context.Context) (count int64, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error { return c.QueryRowContext(ctx, `SELECT count(*) FROM task`).Scan(&count) })
	return
}
