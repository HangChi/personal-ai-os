package chatcontext

import (
	"context"
	"database/sql"
	"errors"

	"personal-ai-os/backend-go/internal/platform/database"
)

type PostgresPreferences struct{ Pool *database.Pool }

func (p PostgresPreferences) Load(ctx context.Context) (value *int, err error) {
	err = p.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var n int
		err := c.QueryRowContext(ctx, `SELECT budget_tokens FROM chat_context_preference WHERE id=1`).Scan(&n)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		if err == nil {
			value = &n
		}
		return err
	})
	return
}
func (p PostgresPreferences) Save(ctx context.Context, n int) error {
	return p.Pool.WithConn(ctx, func(c *sql.Conn) error {
		_, err := c.ExecContext(ctx, `INSERT INTO chat_context_preference(id,budget_tokens) VALUES(1,$1) ON CONFLICT(id) DO UPDATE SET budget_tokens=EXCLUDED.budget_tokens`, n)
		return err
	})
}
