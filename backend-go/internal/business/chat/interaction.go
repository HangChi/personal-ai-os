package chat

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/database"
)

type StreamRequest struct {
	Content       string       `json:"content"`
	UseKnowledge  *bool        `json:"useKnowledge"`
	TopK          *int         `json:"topK"`
	ThinkingLevel *string      `json:"thinkingLevel"`
	Attachments   []Attachment `json:"attachments"`
}

func (r StreamRequest) Validate() error {
	if strip(r.Content) == "" {
		return invalid("content: 不能为空")
	}
	if javaLength(r.Content) > 4000 {
		return invalid("content: 长度不能超过 4000")
	}
	return nil
}
func (r StreamRequest) Knowledge() bool { return r.UseKnowledge != nil && *r.UseKnowledge }
func (r StreamRequest) Limit() int {
	if r.TopK == nil {
		return 4
	}
	return min(20, max(1, *r.TopK))
}

type QuestionOption struct {
	Label       string  `json:"label"`
	Description *string `json:"description"`
}
type Question struct {
	ID       string           `json:"id"`
	RunID    string           `json:"runId"`
	Question string           `json:"question"`
	Options  []QuestionOption `json:"options"`
	Status   string           `json:"status"`
	Answer   *string          `json:"answer"`
}
type Followup struct {
	ID             string        `json:"id"`
	RunID          string        `json:"runId"`
	ConversationID string        `json:"conversationId"`
	Mode           string        `json:"mode"`
	Request        StreamRequest `json:"request"`
	Status         string        `json:"status"`
}
type Interactions struct{ Pool *database.Pool }

func readQuestion(ctx context.Context, tx *sql.Tx, run, id string) (q *Question, e error) {
	q = &Question{}
	var raw string
	sqlText := `SELECT id,run_id,question,options,status,answer FROM agent_question WHERE run_id=$1`
	args := []any{run}
	if id != "" {
		sqlText += ` AND id=$2`
		args = append(args, id)
	} else {
		sqlText += ` AND status='PENDING'`
	}
	e = tx.QueryRowContext(ctx, sqlText, args...).Scan(&q.ID, &q.RunID, &q.Question, &raw, &q.Status, &q.Answer)
	if errors.Is(e, sql.ErrNoRows) {
		return nil, nil
	}
	if e != nil {
		return nil, e
	}
	e = json.Unmarshal([]byte(raw), &q.Options)
	return
}
func readInputs(ctx context.Context, tx *sql.Tx, conversation, run, mode string, pending bool) (out []Followup, err error) {
	out = []Followup{}
	q := `SELECT id,run_id,conversation_id,mode,request,status FROM agent_followup WHERE ($1='' OR conversation_id=$1) AND ($2='' OR run_id=$2) AND ($3='' OR mode=$3)`
	if pending {
		q += ` AND status='PENDING'`
	}
	q += ` ORDER BY seq`
	rows, e := tx.QueryContext(ctx, q, conversation, run, mode)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var v Followup
		var raw string
		if e = rows.Scan(&v.ID, &v.RunID, &v.ConversationID, &v.Mode, &raw, &v.Status); e != nil {
			return nil, e
		}
		if e = json.Unmarshal([]byte(raw), &v.Request); e != nil {
			return nil, e
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
func (s *Interactions) Ask(ctx context.Context, conversation, run, question string, options []QuestionOption) (result *Question, err error) {
	if strip(question) == "" || javaLength(question) > 600 {
		return nil, agent.Conflict("问题须为 1～600 字")
	}
	if options == nil {
		options = []QuestionOption{}
	}
	if len(options) > 4 {
		return nil, agent.Conflict("最多提供 4 个简短选项")
	}
	seen := map[string]bool{}
	for _, v := range options {
		if strip(v.Label) == "" || javaLength(v.Label) > 100 || javaLength(value(v.Description)) > 300 {
			return nil, agent.Conflict("最多提供 4 个简短选项")
		}
		if seen[v.Label] {
			return nil, agent.Conflict("选项名称不能重复")
		}
		seen[v.Label] = true
	}
	question = strip(question)
	digest, _ := idempotency.Digest([]any{question, options})
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var state string
		e := tx.QueryRowContext(ctx, `SELECT status FROM chat_run WHERE id=$1 AND conversation_id=$2 FOR UPDATE`, run, conversation).Scan(&state)
		if errors.Is(e, sql.ErrNoRows) {
			return agent.Conflict("结构化提问需要后台执行任务")
		}
		if e != nil {
			return e
		}
		if state != "RUNNING" {
			return agent.Conflict("任务当前未在执行")
		}
		var existing string
		e = tx.QueryRowContext(ctx, `SELECT id FROM agent_question WHERE run_id=$1 AND digest=$2`, run, digest).Scan(&existing)
		if e == nil {
			result, e = readQuestion(ctx, tx, run, existing)
			return e
		}
		if !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		q, e := readQuestion(ctx, tx, run, "")
		if e != nil {
			return e
		}
		if q != nil {
			return agent.Conflict("请先等待上一个问题的回答")
		}
		result = &Question{ID: agent.UUID(), RunID: run, Question: question, Options: options, Status: "PENDING"}
		raw, _ := json.Marshal(options)
		_, e = tx.ExecContext(ctx, `INSERT INTO agent_question(id,run_id,question,options,digest) VALUES($1,$2,$3,$4,$5)`, result.ID, run, question, string(raw), digest)
		return e
	})
	return
}
func (s *Interactions) Checkpoint(ctx context.Context, run string) error {
	return s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		var state string
		var waiting bool
		e := c.QueryRowContext(ctx, `SELECT r.status, EXISTS(SELECT 1 FROM agent_question WHERE run_id=r.id AND status='PENDING') OR EXISTS(SELECT 1 FROM agent_followup WHERE run_id=r.id AND mode='STEER' AND status='PENDING') FROM chat_run r WHERE id=$1`, run).Scan(&state, &waiting)
		if e != nil {
			return e
		}
		if state != "RUNNING" {
			return context.Canceled
		}
		if waiting {
			return agent.ErrYield
		}
		return nil
	})
}
func (s *Interactions) ApplySteering(ctx context.Context, run string) (out []Followup, err error) {
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var state string
		if e := tx.QueryRowContext(ctx, `SELECT status FROM chat_run WHERE id=$1 FOR UPDATE`, run).Scan(&state); e != nil {
			return e
		}
		if state != "RUNNING" {
			return agent.Conflict("任务当前未在执行")
		}
		var e error
		out, e = readInputs(ctx, tx, "", run, "STEER", true)
		if e != nil {
			return e
		}
		for _, v := range out {
			if _, e = tx.ExecContext(ctx, `UPDATE agent_followup SET status='APPLIED' WHERE id=$1 AND status='PENDING'`, v.ID); e != nil {
				return e
			}
		}
		return nil
	})
	return
}
func (s *Interactions) Transcript(ctx context.Context, run string) (out string, err error) {
	err = s.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		var text strings.Builder
		rows, e := tx.QueryContext(ctx, `SELECT question,answer FROM agent_question WHERE run_id=$1 AND status='ANSWERED' ORDER BY created_at,id`, run)
		if e != nil {
			return e
		}
		for rows.Next() {
			var q, a string
			if e = rows.Scan(&q, &a); e != nil {
				rows.Close()
				return e
			}
			text.WriteString("问题：" + q + "\n用户回答：" + a + "\n")
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		inputs, e := readInputs(ctx, tx, "", run, "STEER", false)
		if e != nil {
			return e
		}
		for _, v := range inputs {
			if v.Status == "APPLIED" {
				text.WriteString("用户补充要求：" + v.Request.Content + "\n")
			}
		}
		out = text.String()
		return nil
	})
	return
}
func (s *Interactions) Tool() agent.Definition {
	return agent.Definition{Keys: []string{"question", "options"}, Model: model.Tool{Name: "askUser", Description: "向用户提出需要澄清的问题，最多四个简短选项；回答不是危险操作的授权，提交后等待回答。", Parameters: agent.ObjectSchema(map[string]any{"question": agent.StringSchema(), "options": map[string]any{"type": "array", "maxItems": 4, "items": agent.ObjectSchema(map[string]any{"label": agent.StringSchema(), "description": agent.StringSchema()}, "label")}}, "question")}, Write: false, Limit: 0, Action: func(ctx context.Context, t *agent.Turn, args map[string]any) (agent.Result, error) {
		var choices []QuestionOption
		if v := args["options"]; v != nil {
			raw, e := json.Marshal(v)
			if e != nil {
				return agent.Result{}, agent.Invalid("选项格式错误")
			}
			if e = json.Unmarshal(raw, &choices); e != nil {
				return agent.Result{}, agent.Invalid("选项格式错误")
			}
		}
		q, e := s.Ask(ctx, t.ConversationID, t.ID, agent.Text(args, "question"), choices)
		message := "问题已展示，等待用户回答"
		if q != nil && q.Status == "ANSWERED" {
			message = "用户已回答，请依据答案继续"
		}
		return agent.OK(message, q), e
	}}
}
