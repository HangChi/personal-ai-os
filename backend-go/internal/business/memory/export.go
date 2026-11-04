package memory

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
)

type ExportContributor struct{}

func (ExportContributor) Key() string { return "memories" }
func (ExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]Entry, error) {
		rows, e := tx.QueryContext(ctx, "SELECT "+columns+" FROM memory ORDER BY id LIMIT $1 OFFSET $2", dataexport.BatchSize, page*dataexport.BatchSize)
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		out := []Entry{}
		for rows.Next() {
			var x Entry
			if e = scan(rows, &x); e != nil {
				return nil, e
			}
			out = append(out, x)
		}
		return out, rows.Err()
	}, nil)
}
