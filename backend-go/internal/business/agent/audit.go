package agent

import (
	"context"
	"database/sql"
	"encoding/json"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

type Invocation struct {
	ID                 int64     `json:"id"`
	Operation          string    `json:"operation"`
	Source             string    `json:"source"`
	Outcome            string    `json:"outcome"`
	Duration           *int      `json:"durationMs"`
	Conversation       *string   `json:"conversationId"`
	Created            time.Time `json:"createdAt"`
	Turn               *string   `json:"turnId"`
	Digest             string    `json:"argumentsDigest"`
	ConfirmationStatus *string   `json:"confirmationStatus"`
}
type Audit struct{ Pool *database.Pool }

func (a *Audit) Search(ctx context.Context, conversation, outcome string, page, size int) (result api.Page[Invocation], err error) {
	page = max(0, page)
	if size <= 0 {
		size = 20
	}
	size = min(100, size)
	conversation = Strip(conversation)
	items := []Invocation{}
	var total int64
	err = a.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		const filter = `source='TOOL' AND ($1='' OR conversation_id=$1) AND ($2='' OR outcome=$2)`
		if e := tx.QueryRowContext(ctx, `SELECT count(*) FROM operation_record WHERE `+filter, conversation, outcome).Scan(&total); e != nil {
			return e
		}
		if api.OffsetOverflows(page, size) {
			return nil
		}
		rows, e := tx.QueryContext(ctx, `SELECT id,operation,source,outcome,duration_ms,conversation_id,created_at,turn_id,request_digest,response_payload FROM operation_record WHERE `+filter+` ORDER BY created_at DESC,id DESC LIMIT $3 OFFSET $4`, conversation, outcome, size, int64(page)*int64(size))
		if e != nil {
			return e
		}
		refs := map[int64]string{}
		ids := []string{}
		for rows.Next() {
			var v Invocation
			var payload *string
			if e = rows.Scan(&v.ID, &v.Operation, &v.Source, &v.Outcome, &v.Duration, &v.Conversation, &v.Created, &v.Turn, &v.Digest, &payload); e != nil {
				rows.Close()
				return e
			}
			if v.Outcome == "REQUIRES_CONFIRMATION" && payload != nil {
				var r Result
				if json.Unmarshal([]byte(*payload), &r) == nil && r.ConfirmationID != nil && uuidPattern.MatchString(*r.ConfirmationID) {
					refs[v.ID] = strings.ToLower(*r.ConfirmationID)
					ids = append(ids, strings.ToLower(*r.ConfirmationID))
				}
			}
			items = append(items, v)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		if len(ids) == 0 {
			return nil
		}
		linked, e := tx.QueryContext(ctx, `SELECT id::text,conversation_id,CASE WHEN status='PENDING' AND expires_at<=now() THEN 'EXPIRED' ELSE status END FROM confirmation WHERE id=ANY($1::uuid[])`, ids)
		if e != nil {
			return e
		}
		defer linked.Close()
		type link struct{ conversation, status string }
		current := map[string]link{}
		for linked.Next() {
			var id string
			var v link
			var c *string
			if e = linked.Scan(&id, &c, &v.status); e != nil {
				return e
			}
			if c != nil {
				v.conversation = *c
			}
			current[id] = v
		}
		if e = linked.Err(); e != nil {
			return e
		}
		for i := range items {
			v := &items[i]
			if c, ok := current[refs[v.ID]]; ok && v.Conversation != nil && c.conversation == *v.Conversation {
				state := c.status
				v.ConfirmationStatus = &state
			}
		}
		return nil
	})
	result = api.PageOfResult(items, page, size, total)
	return
}
