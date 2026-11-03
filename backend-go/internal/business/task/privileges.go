package task

import (
	"context"
	"database/sql"
	"errors"

	"personal-ai-os/backend-go/internal/platform/database"
)

// CheckPrivileges fails P2 startup before exposing routes if a runtime role
// still has only P1 coordination grants. Migrations remain owned separately.
func CheckPrivileges(ctx context.Context, pool *database.Pool) error {
	if pool == nil {
		return errors.New("P2权限检查缺少数据库")
	}
	return pool.WithConn(ctx, func(c *sql.Conn) error {
		var allowed bool
		err := c.QueryRowContext(ctx, `SELECT bool_and(has_table_privilege(current_user,table_name,privilege))
 FROM (VALUES ('public.task','SELECT'),('public.task','INSERT'),('public.task','UPDATE'),('public.task','DELETE'),
 ('public.task_tag','SELECT'),('public.task_tag','INSERT'),('public.task_tag','DELETE'),
 ('public.operation_record','SELECT'),('public.operation_record','INSERT'),('public.operation_record','UPDATE'),('public.operation_record','DELETE'),
 ('public.confirmation','SELECT'),('public.confirmation','INSERT'),('public.confirmation','UPDATE'),
 ('public.reminder','SELECT'),('public.reminder','DELETE'),('public.reminder','UPDATE')) AS required(table_name,privilege)`).Scan(&allowed)
		if err != nil {
			return err
		}
		var sequenceAllowed bool
		if err := c.QueryRowContext(ctx, `SELECT has_sequence_privilege(current_user,'public.operation_record_id_seq','USAGE')`).Scan(&sequenceAllowed); err != nil {
			return err
		}
		if !allowed || !sequenceAllowed {
			return errors.New("P2运行账号缺少任务、幂等、确认或提醒的最小权限")
		}
		return nil
	})
}
