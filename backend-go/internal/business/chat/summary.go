package chat

import (
	"context"
	"errors"
	"strings"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

const summarySystem = "你在为一段对话维护滚动摘要。要求：\n- 只保留对后续对话有用的事实：结论、决定、待办、具体数值、用户明确表达的偏好\n- 丢掉寒暄、重复与过程性描述\n- 用第三人称陈述，不使用「我」「你」\n- 不超过 400 字，只输出摘要正文，不要任何前缀或解释\n"

type PlainModel func(context.Context, model.ChatRequest) (model.ChatResult, error)
type Summary struct {
	Store      Store
	History    *History
	Properties config.ContextSummary
	Model      PlainModel
}
type SummarySnapshot struct {
	ConversationID     string
	Boundary, Previous int
	Existing           *string
	Transcript         string
	LastMessageID      int64
}

func (s *Summary) Render(ctx context.Context, id string) (string, error) {
	if !s.Properties.Enabled || id == "" {
		return "", nil
	}
	var result string
	err := s.Store.Transact(ctx, true, func(tx Tx) error {
		session, err := tx.Session(ctx, id, false)
		var problem *api.BusinessError
		if errors.As(err, &problem) && problem.Code() == api.ResourceNotFound {
			return nil
		}
		if err != nil {
			return err
		}
		if strip(value(session.Summary)) != "" {
			result = "## 早前对话摘要\n\n" + *session.Summary
		}
		return nil
	})
	return result, err
}
func (s *Summary) Snapshot(ctx context.Context, id string, requested *int) (snapshot *SummarySnapshot, err error) {
	if !s.Properties.Enabled {
		return nil, nil
	}
	boundary := 0
	if requested == nil {
		window, e := s.History.Window(ctx, id)
		if e != nil {
			return nil, e
		}
		boundary = window.StartSequence - 1
	} else {
		boundary = *requested
	}
	err = s.Store.Transact(ctx, true, func(tx Tx) error {
		session, e := tx.Session(ctx, id, false)
		var problem *api.BusinessError
		if errors.As(e, &problem) && problem.Code() == api.ResourceNotFound {
			return nil
		}
		if e != nil {
			return e
		}
		if boundary <= session.SummaryUptoSeq {
			return nil
		}
		pending, e := tx.RangeMessages(ctx, id, session.SummaryUptoSeq+1, boundary)
		if e != nil {
			return e
		}
		minimum := s.Properties.MinMessages
		if requested != nil {
			minimum = 1
		}
		if len(pending) < minimum {
			return nil
		}
		batch := []Message{}
		chars := 0
		for _, m := range pending {
			cost := chatcontext.JavaLength(m.Content) + 16
			if len(batch) > 0 && chars+cost > s.Properties.MaxExcerptChars {
				break
			}
			batch = append(batch, m)
			chars += cost
		}
		if len(batch) == 0 {
			return nil
		}
		last := batch[len(batch)-1]
		snapshot = &SummarySnapshot{id, last.SequenceNo, session.SummaryUptoSeq, session.Summary, BuildTranscript(batch, s.Properties.MaxExcerptChars), last.ID}
		return nil
	})
	return
}

func BuildTranscript(messages []Message, limit int) string {
	if limit <= 0 {
		return ""
	}
	picked := []string{}
	used := 0
	for i := len(messages) - 1; i >= 0; i-- {
		role := "助手"
		switch messages[i].Role {
		case "USER":
			role = "用户"
		case "SYSTEM":
			role = "系统"
		case "TOOL":
			role = "工具"
		}
		line := role + "：" + messages[i].Content
		cost := chatcontext.JavaLength(line)
		if len(picked) > 0 && used+cost+1 > limit {
			break
		}
		if len(picked) == 0 && cost > limit {
			marker := "\n[该条历史过长，仅保留首尾供摘要]\n"
			available := limit - chatcontext.JavaLength(marker)
			if available > 0 {
				line = chatcontext.JavaSubstring(line, 0, available/2) + marker + chatcontext.JavaSubstring(line, cost-(available-available/2), cost)
			} else {
				line = chatcontext.JavaSubstring(line, 0, limit)
			}
		}
		picked = append(picked, line)
		used += chatcontext.JavaLength(line) + 1
	}
	for i, j := 0, len(picked)-1; i < j; i, j = i+1, j-1 {
		picked[i], picked[j] = picked[j], picked[i]
	}
	return strings.Join(picked, "\n")
}
func (s *Summary) Apply(ctx context.Context, snapshot *SummarySnapshot, summary string) (applied bool, err error) {
	if snapshot == nil || strip(summary) == "" {
		return false, nil
	}
	summary = strings.Trim(summary, "\x00\x01\x02\x03\x04\x05\x06\x07\x08\x09\x0a\x0b\x0c\x0d\x0e\x0f\x10\x11\x12\x13\x14\x15\x16\x17\x18\x19\x1a\x1b\x1c\x1d\x1e\x1f ")
	summary = chatcontext.JavaSubstring(summary, 0, s.Properties.MaxSummaryChars)
	err = s.Store.Transact(ctx, false, func(tx Tx) error {
		session, e := tx.Session(ctx, snapshot.ConversationID, true)
		var problem *api.BusinessError
		if errors.As(e, &problem) && problem.Code() == api.ResourceNotFound {
			return nil
		}
		if e != nil {
			return e
		}
		if session.SummaryUptoSeq != snapshot.Previous {
			return nil
		}
		// 额外 fencing：rewind 后即使序号复用，也不能把已删除历史的摘要重新写入。
		messageID, e := tx.MessageIdentity(ctx, snapshot.ConversationID, snapshot.Boundary)
		if e != nil {
			return e
		}
		if messageID != snapshot.LastMessageID {
			return nil
		}
		session.Summary = &summary
		session.SummaryUptoSeq = snapshot.Boundary
		if e = tx.SaveSession(ctx, session); e != nil {
			return e
		}
		applied = true
		return nil
	})
	return
}
func (s *Summary) Refresh(ctx context.Context, id string, requested *int) (bool, error) {
	if !s.Properties.Enabled || s.Model == nil {
		return false, nil
	}
	snapshot, err := s.Snapshot(ctx, id, requested)
	if err != nil || snapshot == nil {
		return false, err
	}
	prompt := ""
	if strip(value(snapshot.Existing)) != "" {
		prompt = "已有摘要（需要与新增对话合并）：\n" + *snapshot.Existing + "\n\n"
	}
	prompt += "新增对话：\n" + snapshot.Transcript
	callCtx, cancel := context.WithTimeout(ctx, s.Properties.Timeout)
	defer cancel()
	result, err := s.Model(callCtx, model.ChatRequest{Messages: []model.Message{{Role: "system", Content: summarySystem}, {Role: "user", Content: prompt}}})
	if err != nil {
		return false, err
	}
	return s.Apply(ctx, snapshot, result.Content)
}
func (s *Summary) BeforeRequest(ctx context.Context, id string, boundary int) {
	if boundary <= 0 {
		return
	}
	for i := 0; i < 2; i++ {
		progress, err := s.Refresh(ctx, id, &boundary)
		if err != nil || !progress {
			return
		}
	}
}
