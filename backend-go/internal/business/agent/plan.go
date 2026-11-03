package agent

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

type Step struct {
	Seq        int        `json:"seq"`
	Title      string     `json:"title"`
	Status     string     `json:"status"`
	Detail     *string    `json:"detail"`
	ToolName   *string    `json:"toolName"`
	ToolResult *string    `json:"toolResult"`
	Error      *string    `json:"error"`
	Started    *time.Time `json:"startedAt"`
	Finished   *time.Time `json:"finishedAt"`
}
type Plan struct {
	ID             string    `json:"id"`
	ConversationID string    `json:"conversationId"`
	Goal           string    `json:"goal"`
	Status         string    `json:"status"`
	Steps          []Step    `json:"steps"`
	Created        time.Time `json:"createdAt"`
	Updated        time.Time `json:"updatedAt"`
}
type Plans struct {
	Pool       *database.Pool
	Properties config.PlanProperties
}

func terminal(status string) bool { return status == "DONE" || status == "SKIPPED" }
func firstOpen(p *Plan) int {
	for _, s := range p.Steps {
		if !terminal(s.Status) {
			return s.Seq
		}
	}
	return -1
}
func readPlan(ctx context.Context, tx *sql.Tx, id string, active, lock bool) (p *Plan, err error) {
	q := `SELECT id,conversation_id,goal,status,created_at,updated_at FROM agent_plan WHERE conversation_id=$1`
	if active {
		q += ` AND status='ACTIVE'`
	}
	q += ` ORDER BY created_at DESC,id DESC LIMIT 1`
	if lock {
		q += ` FOR UPDATE`
	}
	p = &Plan{Steps: []Step{}}
	err = tx.QueryRowContext(ctx, q, id).Scan(&p.ID, &p.ConversationID, &p.Goal, &p.Status, &p.Created, &p.Updated)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	rows, e := tx.QueryContext(ctx, `SELECT seq,title,status,detail,tool_name,tool_result,error,started_at,finished_at FROM agent_plan_step WHERE plan_id=$1 ORDER BY seq`, p.ID)
	if e != nil {
		return nil, e
	}
	defer rows.Close()
	for rows.Next() {
		var s Step
		if e = rows.Scan(&s.Seq, &s.Title, &s.Status, &s.Detail, &s.ToolName, &s.ToolResult, &s.Error, &s.Started, &s.Finished); e != nil {
			return nil, e
		}
		p.Steps = append(p.Steps, s)
	}
	return p, rows.Err()
}
func (s *Plans) Get(ctx context.Context, id string, active bool) (p *Plan, err error) {
	err = s.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error { var e error; p, e = readPlan(ctx, tx, id, active, false); return e })
	return
}
func validatedText(text, label string, max int) (string, error) {
	text = Strip(text)
	if text == "" {
		return "", Invalid(label + "不能为空")
	}
	if chatcontext.JavaLength(text) > max {
		return "", Invalid(fmt.Sprintf("%s长度不能超过 %d", label, max))
	}
	return text, nil
}
func (s *Plans) Create(ctx context.Context, id, goal string, titles []string, revise bool) (p *Plan, err error) {
	if len(titles) == 0 {
		return nil, Invalid("计划至少需要一个步骤")
	}
	if len(titles) > 12 {
		return nil, Invalid("计划步骤不能超过 12 步，请先拆成更小的任务")
	}
	titles = append([]string(nil), titles...)
	for i, v := range titles {
		titles[i], err = validatedText(v, fmt.Sprintf("第 %d 步标题", i+1), 200)
		if err != nil {
			return
		}
	}
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		// 会话行锁使跨实例新建/重规划和步骤写入串行；唯一索引仍是最终防线。
		var session string
		if e := tx.QueryRowContext(ctx, `SELECT conversation_id FROM chat_session WHERE conversation_id=$1 FOR UPDATE`, id).Scan(&session); e != nil {
			return e
		}
		old, e := readPlan(ctx, tx, id, true, true)
		if e != nil {
			return e
		}
		if revise && Strip(goal) == "" && old != nil {
			goal = old.Goal
		}
		goal, e = validatedText(goal, "计划目标", 500)
		if e != nil {
			return e
		}
		now := time.Now().UTC()
		if _, e = tx.ExecContext(ctx, `UPDATE agent_plan SET status='ABORTED',updated_at=$2 WHERE conversation_id=$1 AND status='ACTIVE'`, id, now); e != nil {
			return e
		}
		p = &Plan{ID: UUID(), ConversationID: id, Goal: goal, Status: "ACTIVE", Steps: []Step{}, Created: now, Updated: now}
		if _, e = tx.ExecContext(ctx, `INSERT INTO agent_plan(id,conversation_id,goal,status,created_at,updated_at) VALUES($1,$2,$3,'ACTIVE',$4,$4)`, p.ID, id, goal, now); e != nil {
			return e
		}
		for i, title := range titles {
			if _, e = tx.ExecContext(ctx, `INSERT INTO agent_plan_step(id,plan_id,seq,title,status) VALUES($1,$2,$3,$4,'PENDING')`, UUID(), p.ID, i+1, title); e != nil {
				return e
			}
			p.Steps = append(p.Steps, Step{Seq: i + 1, Title: title, Status: "PENDING"})
		}
		return nil
	})
	return
}
func (s *Plans) Change(ctx context.Context, id string, seq int, action, note string) (p *Plan, err error) {
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var session string
		if e := tx.QueryRowContext(ctx, `SELECT conversation_id FROM chat_session WHERE conversation_id=$1 FOR UPDATE`, id).Scan(&session); e != nil {
			return e
		}
		var e error
		p, e = readPlan(ctx, tx, id, true, true)
		if e != nil {
			return e
		}
		if p == nil {
			return Conflict("当前会话没有进行中的计划，请先调用 createPlan")
		}
		if seq < 1 || seq > len(p.Steps) {
			return Invalid(fmt.Sprintf("计划里没有第 %d 步（有效范围 1~%d）", seq, len(p.Steps)))
		}
		step := &p.Steps[seq-1]
		now := time.Now().UTC()
		note = Strip(note)
		switch action {
		case "start":
			if step.Status == "RUNNING" {
				return nil
			}
			if terminal(step.Status) {
				return Conflict(fmt.Sprintf("步骤 %d 已结束（%s），如需重做请先说明原因并重规划", seq, step.Status))
			}
			if seq != firstOpen(p) {
				return Conflict(fmt.Sprintf("计划必须按序推进：第 %d 步还没结束，不能开始第 %d 步。请先执行第 %d 步，或调用 revisePlan 换一条路线", firstOpen(p), seq, firstOpen(p)))
			}
			step.Status = "RUNNING"
			step.Started = &now
			step.Finished = nil
			step.Error = nil
		case "complete":
			if step.Status == "DONE" {
				return nil
			}
			if step.Status == "SKIPPED" {
				return Conflict(fmt.Sprintf("步骤 %d 已被跳过，不能再标记完成", seq))
			}
			if seq != firstOpen(p) {
				return Conflict(fmt.Sprintf("计划必须按序推进：请先完成第 %d 步，不能直接完成第 %d 步", firstOpen(p), seq))
			}
			if step.Status == "FAILED" {
				return Conflict("失败步骤必须先重启或显式跳过，不能直接标记完成")
			}
			if step.ToolName == nil && note == "" {
				return Invalid(fmt.Sprintf("第 %d 步没有任何实际动作或结论，不能标记完成。请先调用完成这一步所需的工具，或在 summary 里写下可核对的结果", seq))
			}
			if step.Started == nil {
				step.Started = &now
			}
			step.Status = "DONE"
			step.Finished = &now
			step.Error = nil
			if note != "" {
				bindStep(step, "结论", note)
			}
		case "fail", "skip":
			if terminal(step.Status) {
				if action == "fail" {
					return Conflict(fmt.Sprintf("步骤 %d 已结束（%s），不能标记失败", seq, step.Status))
				}
				return Conflict(fmt.Sprintf("步骤 %d 已结束（%s），不能跳过", seq, step.Status))
			}
			if note == "" {
				note = "未说明原因"
			}
			step.Status = "FAILED"
			if action == "skip" {
				step.Status = "SKIPPED"
			}
			step.Error = &note
			step.Finished = &now
		default:
			return Invalid("未知计划动作")
		}
		if _, e = tx.ExecContext(ctx, `UPDATE agent_plan_step SET status=$3,tool_name=$4,tool_result=$5,error=$6,started_at=$7,finished_at=$8 WHERE plan_id=$1 AND seq=$2`, p.ID, seq, step.Status, step.ToolName, step.ToolResult, step.Error, step.Started, step.Finished); e != nil {
			return e
		}
		if firstOpen(p) < 0 {
			p.Status = "COMPLETED"
		}
		p.Updated = now
		_, e = tx.ExecContext(ctx, `UPDATE agent_plan SET status=$2,updated_at=$3 WHERE id=$1`, p.ID, p.Status, now)
		return e
	})
	return
}
func truncate(s string, n int) string {
	if chatcontext.JavaLength(s) > n {
		return chatcontext.JavaSubstring(s, 0, n) + "…"
	}
	return s
}
func bindStep(s *Step, name, text string) {
	text = truncate(text, 400)
	if s.ToolName == nil {
		s.ToolName = &name
		s.ToolResult = &text
		return
	}
	line := name + "：" + text
	if s.ToolResult != nil && Strip(*s.ToolResult) != "" {
		line = *s.ToolResult + "\n" + line
	}
	line = truncate(line, 800)
	s.ToolResult = &line
}
func planTool(name string) bool {
	switch name {
	case "createPlan", "startPlanStep", "completePlanStep", "failPlanStep", "skipPlanStep", "revisePlan", "getPlan":
		return true
	}
	return false
}
func (s *Plans) Success(ctx context.Context, id, name, text string) error {
	_, e := s.record(ctx, id, name, text, false)
	return e
}
func (s *Plans) Failure(ctx context.Context, id, name, text string) (string, error) {
	return s.record(ctx, id, name, text, true)
}
func (s *Plans) record(ctx context.Context, id, name, text string, failed bool) (hint string, err error) {
	if planTool(name) || id == "" {
		return "", nil
	}
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var session string
		if e := tx.QueryRowContext(ctx, `SELECT conversation_id FROM chat_session WHERE conversation_id=$1 FOR UPDATE`, id).Scan(&session); e != nil {
			return e
		}
		p, e := readPlan(ctx, tx, id, true, true)
		if e != nil || p == nil {
			return e
		}
		for i := range p.Steps {
			v := &p.Steps[i]
			if v.Status != "RUNNING" {
				continue
			}
			if failed {
				v.Status = "FAILED"
				msg := name + " 失败：" + text
				v.Error = &msg
				now := time.Now().UTC()
				v.Finished = &now
				hint = fmt.Sprintf("步骤 %d 已标记为失败。修正参数后调用 startPlanStep 重启这一步，或调用 revisePlan 换路线；不要重复同参数失败调用。", v.Seq)
			} else {
				bindStep(v, name, text)
			}
			_, e = tx.ExecContext(ctx, `UPDATE agent_plan_step SET status=$3,tool_name=$4,tool_result=$5,error=$6,finished_at=$7 WHERE plan_id=$1 AND seq=$2`, p.ID, v.Seq, v.Status, v.ToolName, v.ToolResult, v.Error, v.Finished)
			if e != nil {
				return e
			}
			_, e = tx.ExecContext(ctx, `UPDATE agent_plan SET updated_at=now() WHERE id=$1`, p.ID)
			return e
		}
		return nil
	})
	return
}
func (s *Plans) Prompt(ctx context.Context, id string) (string, error) {
	if !s.Properties.Enabled || id == "" {
		return "", nil
	}
	p, e := s.Get(ctx, id, true)
	if e != nil || p == nil {
		return "", e
	}
	var out strings.Builder
	done := 0
	var current *Step
	for i := range p.Steps {
		st := &p.Steps[i]
		if terminal(st.Status) {
			done++
		} else if current == nil || st.Status == "RUNNING" {
			current = st
		}
	}
	fmt.Fprintf(&out, "## 执行计划（引擎强制，按序执行）\n\n目标：%s\n进度：%d/%d\n", p.Goal, done, len(p.Steps))
	if current != nil {
		fmt.Fprintf(&out, "\n当前步骤：第 %d 步「%s」\n", current.Seq, current.Title)
		if current.Detail != nil {
			fmt.Fprintf(&out, "完成标准：%s\n", *current.Detail)
		}
		fmt.Fprintf(&out, "执行要求：\n- 只推进这一步骤；在它完成之前不要开始后面的步骤\n- 完成后调用 completePlanStep(%d)，把这一步的产出写进 summary\n- 失败调用 failPlanStep(%d)；不要用同样的参数反复重试\n- 需要换路线时调用 revisePlan，而不是在计划之外自行其是\n", current.Seq, current.Seq)
		if current.Seq < len(p.Steps) {
			out.WriteString("\n后续步骤：")
			for _, st := range p.Steps[current.Seq:min(len(p.Steps), current.Seq+5)] {
				fmt.Fprintf(&out, "%d %s；", st.Seq, st.Title)
			}
			if len(p.Steps)-current.Seq > 5 {
				fmt.Fprintf(&out, "等共 %d 步", len(p.Steps)-current.Seq)
			}
			out.WriteByte('\n')
		}
		out.WriteString("\n计划状态只能通过计划工具改变：不要在回答里声称「已完成第 N 步」却没有调用工具。")
	} else {
		out.WriteString("\n所有步骤已结束，请直接汇总结果并收尾。")
	}
	text := out.String()
	if chatcontext.JavaLength(text) > s.Properties.MaxInjectChars {
		text = chatcontext.JavaSubstring(text, 0, max(0, s.Properties.MaxInjectChars-12)) + "\n……（已截断）"
	}
	return text, nil
}
func (s *Plans) Tools() []Definition {
	var out []Definition
	for _, name := range []string{"createPlan", "revisePlan", "startPlanStep", "completePlanStep", "failPlanStep", "skipPlanStep", "getPlan"} {
		properties := map[string]any{}
		required := []string{}
		switch name {
		case "createPlan", "revisePlan":
			properties["goal"] = StringSchema()
			properties["steps"] = map[string]any{"type": "array", "items": StringSchema(), "minItems": 1, "maxItems": 12}
			properties["reason"] = StringSchema()
			required = []string{"steps"}
			if name == "createPlan" {
				required = append(required, "goal")
			}
		case "getPlan":
		default:
			properties["seq"] = map[string]any{"type": "integer"}
			properties["summary"] = StringSchema()
			properties["reason"] = StringSchema()
			required = []string{"seq"}
		}
		keys := map[string][]string{"createPlan": {"goal", "steps"}, "revisePlan": {"reason", "steps", "goal"}, "getPlan": {}, "startPlanStep": {"seq"}, "completePlanStep": {"seq", "summary"}, "failPlanStep": {"seq", "reason"}, "skipPlanStep": {"seq", "reason"}}[name]
		d := Definition{Keys: keys, Model: model.Tool{Name: name, Description: "管理当前会话执行计划：按序推进，完成须有工具回执或可核对结论", Parameters: ObjectSchema(properties, required...)}, Write: name != "getPlan", Limit: 0}
		d.Action = func(ctx context.Context, t *Turn, args map[string]any) (Result, error) {
			var p *Plan
			var e error
			switch name {
			case "getPlan":
				p, e = s.Get(ctx, t.ConversationID, false)
			case "createPlan", "revisePlan":
				raw, ok := args["steps"].([]any)
				if !ok {
					return Result{}, Invalid("steps 必须是步骤标题数组")
				}
				titles := []string{}
				for _, v := range raw {
					x, ok := v.(string)
					if !ok {
						return Result{}, Invalid("步骤标题必须是文本")
					}
					titles = append(titles, x)
				}
				p, e = s.Create(ctx, t.ConversationID, Text(args, "goal"), titles, name == "revisePlan")
			default:
				if args["seq"] == nil {
					return Result{}, Invalid("步骤序号不能为空")
				}
				seq, err := Integer(args, "seq")
				if err != nil {
					return Result{}, err
				}
				action := map[string]string{"startPlanStep": "start", "completePlanStep": "complete", "failPlanStep": "fail", "skipPlanStep": "skip"}[name]
				note := Text(args, "reason")
				if name == "completePlanStep" {
					note = Text(args, "summary")
				}
				p, e = s.Change(ctx, t.ConversationID, seq, action, note)
			}
			message := "当前会话还没有执行计划。"
			if p != nil {
				done := 0
				for _, step := range p.Steps {
					if terminal(step.Status) {
						done++
					}
				}
				message = fmt.Sprintf("计划进度 %d/%d（%s）", done, len(p.Steps), p.Status)
				switch name {
				case "createPlan":
					message = fmt.Sprintf("已建立 %d 步计划，请从第 1 步开始。", len(p.Steps))
				case "revisePlan":
					message = fmt.Sprintf("已按新路线重建计划，共 %d 步。", len(p.Steps))
				case "startPlanStep":
					message = fmt.Sprintf("第 %v 步已开始。", args["seq"])
				case "failPlanStep":
					message = fmt.Sprintf("第 %v 步已标记失败。", args["seq"])
				}
			}
			return OK(message, p), e
		}
		out = append(out, d)
	}
	return out
}
