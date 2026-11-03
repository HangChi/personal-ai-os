package idempotency

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
)

type PostgresStore struct{ pool *database.Pool }

func NewPostgresStore(pool *database.Pool) (*PostgresStore, error) {
	if pool == nil {
		return nil, errors.New("幂等 PostgreSQL 连接池不能为空")
	}
	return &PostgresStore{pool: pool}, nil
}

func (s *PostgresStore) Find(ctx context.Context, key string) (record Record, found bool, err error) {
	err = s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return scanRecord(conn.QueryRowContext(ctx, selectRecord+` WHERE idempotency_key=$1`, key), &record)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Record{}, false, nil
	}
	return record, err == nil, err
}

func (s *PostgresStore) Claim(ctx context.Context, candidate Record) (winner Record, claimed bool, err error) {
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		result, execErr := tx.ExecContext(ctx, `INSERT INTO operation_record
 (idempotency_key,source,operation,request_digest,conversation_id,turn_id,outcome,created_at)
 VALUES($1,$2,$3,$4,NULLIF($5,''),NULLIF($6,''),'IN_PROGRESS',$7)
 ON CONFLICT(idempotency_key) DO NOTHING`, candidate.Key, candidate.Source, candidate.Operation,
			candidate.Digest, candidate.ConversationID, candidate.TurnID, candidate.CreatedAt)
		if execErr != nil {
			return execErr
		}
		rows, rowsErr := result.RowsAffected()
		if rowsErr != nil {
			return rowsErr
		}
		claimed = rows == 1
		if claimed {
			winner = candidate
			return nil
		}
		return scanRecord(tx.QueryRowContext(ctx, selectRecord+` WHERE idempotency_key=$1`, candidate.Key), &winner)
	})
	return
}

func (s *PostgresStore) DeleteStaleHTTP(ctx context.Context, record Record, threshold time.Time) (bool, error) {
	var deleted bool
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		result, err := conn.ExecContext(ctx, `DELETE FROM operation_record
 WHERE idempotency_key=$1 AND request_digest=$2 AND source='HTTP' AND outcome='IN_PROGRESS' AND created_at=$3 AND created_at<$4`,
			record.Key, record.Digest, record.CreatedAt, threshold)
		if err != nil {
			return err
		}
		rows, err := result.RowsAffected()
		deleted = rows == 1
		return err
	})
	return deleted, err
}

func (s *PostgresStore) Complete(ctx context.Context, key string, outcome Outcome, payload *string, duration int) error {
	return s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		result, err := tx.ExecContext(ctx, `UPDATE operation_record SET outcome=$2,response_payload=$3,duration_ms=$4
 WHERE idempotency_key=$1 AND outcome='IN_PROGRESS'`, key, outcome, payload, duration)
		if err != nil {
			return err
		}
		rows, err := result.RowsAffected()
		if err != nil {
			return err
		}
		if rows != 1 {
			return errors.New("幂等占位已丢失或已完成")
		}
		return nil
	})
}

const selectRecord = `SELECT id,idempotency_key,source,operation,request_digest,
 COALESCE(conversation_id,''),COALESCE(turn_id,''),outcome,duration_ms,response_payload,created_at FROM operation_record`

type rowScanner interface{ Scan(...any) error }

func scanRecord(row rowScanner, record *Record) error {
	return row.Scan(&record.ID, &record.Key, &record.Source, &record.Operation, &record.Digest,
		&record.ConversationID, &record.TurnID, &record.Outcome, &record.DurationMS, &record.ResponsePayload, &record.CreatedAt)
}
