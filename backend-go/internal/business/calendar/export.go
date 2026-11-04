package calendar

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
)

type ExportContributor struct{}

func (ExportContributor) Key() string { return "calendarEvents" }
func (ExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]Event, error) {
		rows, e := tx.QueryContext(ctx, "SELECT "+eventColumns+" FROM calendar_event ORDER BY id LIMIT $1 OFFSET $2", dataexport.BatchSize, page*dataexport.BatchSize)
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		out := []Event{}
		for rows.Next() {
			x, e := scanEvent(rows)
			if e != nil {
				return nil, e
			}
			out = append(out, x)
		}
		return out, rows.Err()
	}, nil)
}
