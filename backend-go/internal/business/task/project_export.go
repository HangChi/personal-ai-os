package task

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
)

type ProjectExportContributor struct{}

func (ProjectExportContributor) Key() string { return "projects" }
func (ProjectExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]Project, error) {
		rows, e := tx.QueryContext(ctx, `SELECT id::text,name,description,archived,created_at,updated_at FROM project ORDER BY id LIMIT $1 OFFSET $2`, dataexport.BatchSize, page*dataexport.BatchSize)
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		out := []Project{}
		for rows.Next() {
			var p Project
			if e = rows.Scan(&p.ID, &p.Name, &p.Description, &p.Archived, &p.CreatedAt, &p.UpdatedAt); e != nil {
				return nil, e
			}
			out = append(out, p)
		}
		return out, rows.Err()
	}, nil)
}
