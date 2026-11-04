package knowledge

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
)

type ExportContributor struct{ Store *Store }

func (ExportContributor) Key() string { return "knowledgeDocuments" }
func (c ExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]Document, error) {
		rows, e := tx.QueryContext(ctx, "SELECT "+documentColumns+" FROM knowledge_document ORDER BY id LIMIT $1 OFFSET $2", dataexport.BatchSize, page*dataexport.BatchSize)
		if e != nil {
			return nil, e
		}
		defer rows.Close()
		out := []Document{}
		for rows.Next() {
			var d Document
			if e = scanDocument(rows, &d); e != nil {
				return nil, e
			}
			out = append(out, d)
		}
		return out, rows.Err()
	}, func(d Document) error {
		sources, e := readSources(ctx, tx, []string{d.ID}, false)
		if e != nil {
			return e
		}
		var source *SourceInfo
		if len(sources) > 0 {
			source = &sources[0].Source
		}
		w.Raw(`{"document":`)
		w.JSON(d)
		w.Raw(`,"source":`)
		w.JSON(source)
		w.Raw(`,"chunks":`)
		_, e = dataexport.Array(ctx, w, func(page int) ([]string, error) {
			rows, e := tx.QueryContext(ctx, "SELECT content FROM "+c.Store.table+` WHERE metadata->>'document_id'=$1 ORDER BY CASE WHEN metadata->>'chunk_index' ~ '^[0-9]+$' THEN (metadata->>'chunk_index')::numeric ELSE 2147483647 END,id LIMIT $2 OFFSET $3`, d.ID, dataexport.BatchSize, page*dataexport.BatchSize)
			if e != nil {
				return nil, e
			}
			defer rows.Close()
			out := []string{}
			for rows.Next() {
				var text string
				if e = rows.Scan(&text); e != nil {
					return nil, e
				}
				out = append(out, text)
			}
			return out, rows.Err()
		}, nil)
		w.Raw("}")
		return e
	})
}
