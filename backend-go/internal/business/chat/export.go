package chat

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"strconv"
)

type ExportContributor struct{}

func (ExportContributor) Key() string { return "sessions" }
func (ExportContributor) Write(ctx context.Context, tx *sql.Tx, w *dataexport.Writer) (int, error) {
	return dataexport.Array(ctx, w, func(page int) ([]SessionDTO, error) {
		items, _, e := (&postgresTx{tx: tx}).Sessions(ctx, SessionQuery{Page: page, Size: dataexport.BatchSize, Export: true})
		out := []SessionDTO{}
		for _, s := range items {
			out = append(out, s.DTO())
		}
		return out, e
	}, func(s SessionDTO) error {
		w.Raw(`{"session":`)
		w.JSON(s)
		w.Raw(`,"messages":`)
		_, e := dataexport.Array(ctx, w, func(page int) ([]Message, error) { return exportMessages(ctx, tx, s.ConversationID, page) }, nil)
		w.Raw("}")
		return e
	})
}
func exportMessages(ctx context.Context, tx *sql.Tx, id string, page int) ([]Message, error) {
	rows, e := tx.QueryContext(ctx, `SELECT id,message_type,content,sequence_no,created_at,attachments FROM chat_message WHERE conversation_id=$1 ORDER BY sequence_no LIMIT $2 OFFSET $3`, id, dataexport.BatchSize, page*dataexport.BatchSize)
	if e != nil {
		return nil, e
	}
	items := []Message{}
	for rows.Next() {
		var m Message
		var raw *string
		if e = rows.Scan(&m.ID, &m.Role, &m.Content, &m.SequenceNo, &m.CreatedAt, &raw); e != nil {
			rows.Close()
			return nil, e
		}
		m.Attachments = snapshots(raw)
		m.Citations = []Citation{}
		items = append(items, m)
	}
	e = rows.Err()
	rows.Close()
	if e != nil {
		return nil, e
	}
	if len(items) > 0 {
		ids := map[int64]int{}
		parameters := []string{}
		args := []any{}
		for i, m := range items {
			ids[m.ID] = i
			parameters = append(parameters, "$"+strconv.Itoa(i+1))
			args = append(args, m.ID)
		}
		e = (&postgresTx{tx: tx}).loadCitations(ctx, items, ids, parameters, args)
	}
	return items, e
}
