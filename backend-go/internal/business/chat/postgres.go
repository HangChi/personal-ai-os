package chat

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

type PostgresStore struct {
	pool        *database.Pool
	attachments *Attachments
}
type postgresTx struct {
	tx          *sql.Tx
	attachments *Attachments
}

func NewPostgresStore(pool *database.Pool) (*PostgresStore, error) {
	if pool == nil {
		return nil, errors.New("对话数据库连接池不能为空")
	}
	return &PostgresStore{pool: pool}, nil
}
func (s *PostgresStore) Transact(ctx context.Context, readOnly bool, action func(Tx) error) error {
	if action == nil {
		return errors.New("对话事务回调不能为空")
	}
	opts := sql.TxOptions{ReadOnly: readOnly}
	if readOnly {
		opts.Isolation = sql.LevelRepeatableRead
	}
	return s.pool.WithTx(ctx, opts, func(tx *sql.Tx) error {
		if !readOnly && s.attachments != nil {
			if err := s.attachments.lockBinding(ctx, tx); err != nil {
				return err
			}
		}
		return action(&postgresTx{tx: tx, attachments: s.attachments})
	})
}

const sessionColumns = `s.conversation_id,s.title,s.created_at,s.updated_at,s.chat_model,s.context_summary,s.summary_upto_seq`

type scanner interface{ Scan(...any) error }

func scanSession(row scanner, s *Session) error {
	return row.Scan(&s.ConversationID, &s.Title, &s.CreatedAt, &s.UpdatedAt, &s.ChatModel, &s.Summary, &s.SummaryUptoSeq, &s.MessageCount)
}
func (t *postgresTx) Session(ctx context.Context, id string, lock bool) (s Session, err error) {
	query := `SELECT ` + sessionColumns + `,(SELECT count(*) FROM chat_message m WHERE m.conversation_id=s.conversation_id) FROM chat_session s WHERE s.conversation_id=$1`
	if lock {
		query += ` FOR UPDATE OF s`
	}
	err = scanSession(t.tx.QueryRowContext(ctx, query, id), &s)
	if errors.Is(err, sql.ErrNoRows) {
		err = notFound(id)
	}
	return
}
func (t *postgresTx) InsertSession(ctx context.Context, s Session) error {
	_, err := t.tx.ExecContext(ctx, `INSERT INTO chat_session(conversation_id,title,created_at,updated_at,chat_model,context_summary,summary_upto_seq) VALUES($1,$2,$3,$4,$5,$6,$7)`, s.ConversationID, s.Title, s.CreatedAt, s.UpdatedAt, s.ChatModel, s.Summary, s.SummaryUptoSeq)
	return err
}
func (t *postgresTx) SaveSession(ctx context.Context, s Session) error {
	_, err := t.tx.ExecContext(ctx, `UPDATE chat_session SET title=$2,updated_at=$3,chat_model=$4,context_summary=$5,summary_upto_seq=$6 WHERE conversation_id=$1`, s.ConversationID, s.Title, s.UpdatedAt, s.ChatModel, s.Summary, s.SummaryUptoSeq)
	return err
}
func (t *postgresTx) Sessions(ctx context.Context, q SessionQuery) (items []Session, total int64, err error) {
	items = []Session{}
	pattern := ""
	if strip(q.Keyword) != "" {
		pattern = likePattern(q.Keyword)
	}
	where := ` WHERE ($1::text='' OR lower(s.title) LIKE $1 ESCAPE '\')`
	if err = t.tx.QueryRowContext(ctx, `SELECT count(*) FROM chat_session s`+where, pattern).Scan(&total); err != nil {
		return
	}
	if !q.All && api.OffsetOverflows(q.Page, q.Size) {
		return
	}
	query := `SELECT ` + sessionColumns + `,(SELECT count(*) FROM chat_message m WHERE m.conversation_id=s.conversation_id) FROM chat_session s` + where + ` ORDER BY s.updated_at DESC`
	if q.Export {
		query = `SELECT ` + sessionColumns + `,(SELECT count(*) FROM chat_message m WHERE m.conversation_id=s.conversation_id) FROM chat_session s` + where + ` ORDER BY s.conversation_id`
	}
	args := []any{pattern}
	if !q.All {
		query += ` LIMIT $2 OFFSET $3`
		args = append(args, q.Size, int64(q.Page)*int64(q.Size))
	}
	rows, err := t.tx.QueryContext(ctx, query, args...)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var s Session
		if err = scanSession(rows, &s); err != nil {
			return
		}
		items = append(items, s)
	}
	err = rows.Err()
	return
}
func (t *postgresTx) CountMessages(ctx context.Context, id string) (count int64, err error) {
	err = t.tx.QueryRowContext(ctx, `SELECT count(*) FROM chat_message WHERE conversation_id=$1`, id).Scan(&count)
	return
}
func (t *postgresTx) MaxSequence(ctx context.Context, id string) (seq int, err error) {
	err = t.tx.QueryRowContext(ctx, `SELECT coalesce(max(sequence_no),0) FROM chat_message WHERE conversation_id=$1`, id).Scan(&seq)
	return
}

func (t *postgresTx) RangeMessages(ctx context.Context, id string, from, through int) ([]Message, error) {
	rows, err := t.tx.QueryContext(ctx, `SELECT id,message_type,content,sequence_no,created_at FROM chat_message WHERE conversation_id=$1 AND sequence_no>=$2 AND sequence_no<=$3 ORDER BY sequence_no`, id, from, through)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []Message{}
	for rows.Next() {
		var m Message
		if err = rows.Scan(&m.ID, &m.Role, &m.Content, &m.SequenceNo, &m.CreatedAt); err != nil {
			return nil, err
		}
		result = append(result, m)
	}
	return result, rows.Err()
}
func (t *postgresTx) MessageIdentity(ctx context.Context, id string, seq int) (messageID int64, err error) {
	err = t.tx.QueryRowContext(ctx, `SELECT id FROM chat_message WHERE conversation_id=$1 AND sequence_no=$2`, id, seq).Scan(&messageID)
	if errors.Is(err, sql.ErrNoRows) {
		err = nil
	}
	return
}
func (t *postgresTx) Messages(ctx context.Context, id string, q MessageQuery) (items []Message, err error) {
	items = []Message{}
	query := `SELECT id,message_type,content,sequence_no,created_at,attachments FROM chat_message WHERE conversation_id=$1`
	args := []any{id}
	if q.Limit != nil && q.BeforeSeq != nil {
		args = append(args, *q.BeforeSeq)
		query += ` AND sequence_no<$2`
	}
	query += ` ORDER BY sequence_no`
	if q.Limit != nil {
		query += ` DESC`
		args = append(args, *q.Limit)
		query += ` LIMIT $` + strconv.Itoa(len(args))
	}
	rows, err := t.tx.QueryContext(ctx, query, args...)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var m Message
		var raw *string
		if err = rows.Scan(&m.ID, &m.Role, &m.Content, &m.SequenceNo, &m.CreatedAt, &raw); err != nil {
			return
		}
		m.Attachments = snapshots(raw)
		m.Citations = []Citation{}
		items = append(items, m)
	}
	if err = rows.Err(); err != nil {
		return
	}
	if err = rows.Close(); err != nil {
		return
	}
	if q.Limit != nil {
		for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
			items[i], items[j] = items[j], items[i]
		}
	}
	if len(items) == 0 {
		return
	}
	// 无上限历史/导出也按固定批次查引用，避免超过 PostgreSQL 参数上限；不逐消息查询。
	byID := make(map[int64]int, len(items))
	for i, m := range items {
		byID[m.ID] = i
	}
	for start := 0; start < len(items); start += citationBatchSize {
		batch := items[start:min(start+citationBatchSize, len(items))]
		placeholders := make([]string, len(batch))
		args := make([]any, len(batch))
		for i, m := range batch {
			placeholders[i] = "$" + strconv.Itoa(i+1)
			args[i] = m.ID
		}
		if err = t.loadCitations(ctx, items, byID, placeholders, args); err != nil {
			return
		}
	}
	return
}

const citationBatchSize = 512

func (t *postgresTx) loadCitations(ctx context.Context, items []Message, byID map[int64]int, placeholders []string, args []any) error {
	rows, err := t.tx.QueryContext(ctx, `SELECT message_id,citation_index,document_id,document_title,filename,chunk_index,content FROM chat_message_citation WHERE message_id IN (`+strings.Join(placeholders, ",")+`) ORDER BY message_id,citation_index`, args...)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var c Citation
		if err := rows.Scan(&id, &c.Index, &c.DocumentID, &c.DocumentTitle, &c.Filename, &c.ChunkIndex, &c.Content); err != nil {
			return err
		}
		if c.DocumentID == webCitationDocumentID {
			if link, excerpt, ok := strings.Cut(c.Content, "\n"); ok {
				c.URL, c.Content = link, excerpt
			}
		}
		i := byID[id]
		items[i].Citations = append(items[i].Citations, c)
	}
	return rows.Err()
}

func (t *postgresTx) InsertMessage(ctx context.Context, id string, m NewMessage, seq int, now time.Time) error {
	var raw *string
	if len(m.Attachments) > 0 {
		if t.attachments != nil {
			verified, err := t.attachments.Verify(ctx, m.Attachments)
			if err != nil {
				return err
			}
			m.Attachments = verified
		}
		data, err := json.Marshal(m.Attachments)
		if err != nil {
			return err
		}
		raw = ptr(string(data))
	}
	_, err := t.tx.ExecContext(ctx, `INSERT INTO chat_message(conversation_id,message_type,content,sequence_no,created_at,attachments) VALUES($1,$2,$3,$4,$5,$6)`, id, m.Role, m.Content, seq, now, raw)
	return err
}
func (t *postgresTx) AttachmentSnapshots(ctx context.Context, id string, from *int) (items []Attachment, err error) {
	items = []Attachment{}
	query := `SELECT attachments FROM chat_message WHERE conversation_id=$1 AND attachments IS NOT NULL`
	args := []any{id}
	if from != nil {
		query += ` AND sequence_no>=$2`
		args = append(args, *from)
	}
	rows, err := t.tx.QueryContext(ctx, query, args...)
	if err != nil {
		return
	}
	defer rows.Close()
	for rows.Next() {
		var raw *string
		if err = rows.Scan(&raw); err != nil {
			return
		}
		items = append(items, snapshots(raw)...)
	}
	err = rows.Err()
	return
}
func (t *postgresTx) DeleteMessages(ctx context.Context, id string, from *int) (int, error) {
	query := `DELETE FROM chat_message WHERE conversation_id=$1`
	args := []any{id}
	if from != nil {
		query += ` AND sequence_no>=$2`
		args = append(args, *from)
	}
	result, err := t.tx.ExecContext(ctx, query, args...)
	if err != nil {
		return 0, err
	}
	n, err := result.RowsAffected()
	return int(n), err
}
func (t *postgresTx) DeleteSession(ctx context.Context, id string) error {
	_, err := t.tx.ExecContext(ctx, `DELETE FROM chat_session WHERE conversation_id=$1`, id)
	return err
}
func (t *postgresTx) InsertCitations(ctx context.Context, id int64, hits []Citation, now time.Time) error {
	for i, c := range hits {
		content := c.Content
		if c.DocumentID == webCitationDocumentID {
			content = c.URL + "\n" + content
		}
		if _, err := t.tx.ExecContext(ctx, `INSERT INTO chat_message_citation(message_id,citation_index,document_id,document_title,filename,chunk_index,content,created_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8)`, id, i+1, c.DocumentID, c.DocumentTitle, c.Filename, c.ChunkIndex, content, now); err != nil {
			return err
		}
	}
	return nil
}
