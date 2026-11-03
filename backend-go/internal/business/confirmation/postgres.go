package confirmation

import (
	"context"
	"database/sql"
	"errors"

	"personal-ai-os/backend-go/internal/platform/database"
	"time"
)

type PostgresStore struct{ pool *database.Pool }

func NewPostgresStore(pool *database.Pool) (*PostgresStore, error) {
	if pool == nil {
		return nil, errors.New("确认数据库连接池不能为空")
	}
	return &PostgresStore{pool}, nil
}
func (s *PostgresStore) Create(ctx context.Context, r Record) (Record, error) {
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, `INSERT INTO confirmation(token,conversation_id,action,summary,payload,affected_count,status,created_at,expires_at) VALUES($1,NULLIF($2,''),$3,$4,$5,$6,'PENDING',$7,$8) RETURNING id::text`, r.Token, r.ConversationID, r.Action, r.Summary, r.Payload, r.AffectedCount, r.CreatedAt, r.ExpiresAt).Scan(&r.ID)
	})
	return r, err
}

const fields = `SELECT id::text,token,COALESCE(conversation_id,''),action,summary,payload,affected_count,status,created_at,expires_at,resolved_at FROM confirmation`

type scanner interface{ Scan(...any) error }

func scan(row scanner, r *Record) error {
	return row.Scan(&r.ID, &r.Token, &r.ConversationID, &r.Action, &r.Summary, &r.Payload, &r.AffectedCount, &r.Status, &r.CreatedAt, &r.ExpiresAt, &r.ResolvedAt)
}
func (s *PostgresStore) Get(ctx context.Context, id string) (r Record, found bool, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error { return scan(c.QueryRowContext(ctx, fields+` WHERE id=$1::uuid`, id), &r) })
	if errors.Is(err, sql.ErrNoRows) {
		return Record{}, false, nil
	}
	return r, err == nil, err
}
func (s *PostgresStore) List(ctx context.Context, conversation string, status Status) (result []Record, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, fields+` WHERE status=$1 AND ($2='' OR conversation_id=$2) ORDER BY created_at DESC`, status, conversation)
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var r Record
			if e = scan(rows, &r); e != nil {
				return e
			}
			result = append(result, r)
		}
		return rows.Err()
	})
	return
}
func (s *PostgresStore) CompareAndSet(ctx context.Context, id string, expected, next Status, now time.Time) (bool, error) {
	var updated bool
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		res, e := c.ExecContext(ctx, `UPDATE confirmation SET status=$3,resolved_at=$4 WHERE id=$1::uuid AND status=$2`, id, expected, next, now)
		if e != nil {
			return e
		}
		n, e := res.RowsAffected()
		updated = n == 1
		return e
	})
	return updated, err
}
