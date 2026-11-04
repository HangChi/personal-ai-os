package memory

import (
	"context"
	"database/sql"
	"errors"
	"math"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
)

func (s *Store) UseEmbedding(client model.EmbeddingModel, tag string, threshold float64) {
	s.embedding = client
	s.modelTag = tag
	s.threshold = threshold
}

func (s *Store) vector(ctx context.Context, text string, timeout time.Duration) string {
	if s.embedding == nil || strings.TrimSpace(text) == "" {
		return ""
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	result, err := s.embedding.Embed(ctx, model.EmbeddingRequest{Input: []string{text}})
	if err != nil || len(result.Results) != 1 || len(result.Results[0].Vector) != 1024 {
		return ""
	}
	var vector strings.Builder
	vector.WriteByte('[')
	nonzero := false
	for i, value := range result.Results[0].Vector {
		if math.IsNaN(float64(value)) || math.IsInf(float64(value), 0) {
			return ""
		}
		nonzero = nonzero || value != 0
		if i > 0 {
			vector.WriteByte(',')
		}
		vector.WriteString(strconv.FormatFloat(float64(value), 'g', -1, 32))
	}
	vector.WriteByte(']')
	if !nonzero {
		return ""
	}
	return vector.String()
}

// Serialize near-duplicate decisions across replicas. The model call runs before
// opening the transaction so a slow embedding never holds a connection or lock.
func lockVectors(ctx context.Context, tx *sql.Tx, vector string) error {
	if vector == "" {
		return nil
	}
	_, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(346012304, 500)`)
	return err
}

func (s *Store) applyVector(ctx context.Context, tx *sql.Tx, entry *Entry, vector string) error {
	if vector == "" {
		return nil
	}
	if entry.SupersededBy == nil {
		var previous string
		var pinned bool
		err := tx.QueryRowContext(ctx, `SELECT id::text,pinned FROM memory
		WHERE id<>$1 AND superseded_by IS NULL AND embedding_model=$2 AND embedding IS NOT NULL
		AND (embedding <=> $3::vector)<=1-$4::double precision
		ORDER BY embedding <=> $3::vector LIMIT 1 FOR UPDATE`, entry.ID, s.modelTag, vector, s.threshold).Scan(&previous, &pinned)
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if err == nil {
			if _, err := tx.ExecContext(ctx, "UPDATE memory SET superseded_by=$2,pinned=false WHERE id=$1", previous, entry.ID); err != nil {
				return err
			}
			entry.Pinned = entry.Pinned || pinned
		}
	}
	_, err := tx.ExecContext(ctx, `UPDATE memory SET embedding=$2::vector,embedding_model=$3,embedded_at=now(),pinned=$4
		WHERE id=$1`, entry.ID, vector, s.modelTag, entry.Pinned)
	return err
}

func (s *Store) similarities(ctx context.Context, vector string, limit int) map[string]float64 {
	if vector == "" {
		return nil
	}
	result := make(map[string]float64)
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, `SELECT id::text,1-(embedding <=> $1::vector) FROM memory
			WHERE superseded_by IS NULL AND embedding_model=$2 AND embedding IS NOT NULL
			ORDER BY embedding <=> $1::vector LIMIT $3`, vector, s.modelTag, limit)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id string
			var score float64
			if err := rows.Scan(&id, &score); err != nil {
				return err
			}
			if !math.IsNaN(score) {
				result[id] = score
			}
		}
		return rows.Err()
	})
	if err != nil {
		return nil
	}
	return result
}

type pendingEmbedding struct{ id, content, hash string }

func (s *Store) pendingEmbeddings(ctx context.Context, limit int) ([]pendingEmbedding, error) {
	var pending []pendingEmbedding
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, `SELECT id::text,content,content_hash FROM memory
			WHERE embedded_at IS NULL AND superseded_by IS NULL ORDER BY id LIMIT $1`, limit)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var row pendingEmbedding
			if err := rows.Scan(&row.id, &row.content, &row.hash); err != nil {
				return err
			}
			pending = append(pending, row)
		}
		return rows.Err()
	})
	return pending, err
}

func (s *Store) Backfill(ctx context.Context, limit int) error {
	if s.embedding == nil {
		return nil
	}
	for {
		pending, err := s.pendingEmbeddings(ctx, limit)
		if err != nil || len(pending) == 0 {
			return err
		}
		progressed, failures := false, 0
		for _, row := range pending {
			if err := ctx.Err(); err != nil {
				return err
			}
			vector := s.vector(ctx, row.content, 10*time.Second)
			if vector == "" {
				failures++
				if failures >= 3 {
					return nil
				}
				continue
			}
			err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
				// An edit during embedding invalidates the snapshot.
				change, err := conn.ExecContext(ctx, `UPDATE memory SET embedding=$2::vector,embedding_model=$3,embedded_at=now()
					WHERE id=$1 AND content_hash=$4 AND embedded_at IS NULL AND superseded_by IS NULL`, row.id, vector, s.modelTag, row.hash)
				if err != nil {
					return err
				}
				count, err := change.RowsAffected()
				progressed = progressed || count > 0
				return err
			})
			if err != nil {
				return err
			}
			failures = 0
		}
		if !progressed {
			return nil
		}
	}
}
