package task

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"time"
)

func (c ExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]DTO, error) {
		rows, e := tx.QueryContext(ctx, "SELECT "+columns+" FROM task ORDER BY id LIMIT $1 OFFSET $2", dataexport.BatchSize, page*dataexport.BatchSize)
		if e != nil {
			return nil, e
		}
		items := []Task{}
		for rows.Next() {
			var t Task
			if e = scan(rows, &t); e != nil {
				rows.Close()
				return nil, e
			}
			items = append(items, t)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return nil, e
		}
		out := []DTO{}
		for _, t := range items {
			if e = readTags(ctx, tx, &t); e != nil {
				return nil, e
			}
			out = append(out, t.DTO(time.Now()))
		}
		return out, nil
	}, nil)
}
