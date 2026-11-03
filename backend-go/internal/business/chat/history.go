package chat

import (
	"context"
	"errors"

	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
)

type History struct {
	Store  Store
	Budget *chatcontext.Budget
}
type HistoryWindow struct {
	Messages      []Message
	StartSequence int
}

// Window 持久历史不受预算影响；只截取送模型的副本，至少保留最新一条。
func (h *History) Window(ctx context.Context, id string) (result HistoryWindow, err error) {
	result.Messages = []Message{}
	err = h.Store.Transact(ctx, true, func(tx Tx) error {
		session, e := tx.Session(ctx, id, false)
		var problem *api.BusinessError
		if errors.As(e, &problem) && problem.Code() == api.ResourceNotFound {
			result.StartSequence = 1
			return nil
		}
		if e != nil {
			return e
		}
		limit := h.Budget.AI.Memory.MaxMessages
		rows, e := tx.Messages(ctx, id, MessageQuery{Limit: &limit})
		if e != nil {
			return e
		}
		budget := h.Budget.CharBudget(ctx, value(session.ChatModel))
		used, start := 0, len(rows)
		for i := len(rows) - 1; i >= 0; i-- {
			cost := chatcontext.JavaLength(rows[i].Content)
			if start < len(rows) && used+cost > budget {
				break
			}
			used += cost
			start = i
		}
		result.Messages = rows[start:]
		if len(result.Messages) > 0 {
			result.StartSequence = result.Messages[0].SequenceNo
			return nil
		}
		last, e := tx.MaxSequence(ctx, id)
		result.StartSequence = last + 1
		return e
	})
	return
}
func (h *History) ModelMessages(ctx context.Context, id string) ([]model.Message, error) {
	window, err := h.Window(ctx, id)
	if err != nil {
		return nil, err
	}
	result := make([]model.Message, 0, len(window.Messages))
	for _, message := range window.Messages {
		role := "assistant"
		switch message.Role {
		case "USER":
			role = "user"
		case "SYSTEM":
			role = "system"
		}
		result = append(result, model.Message{Role: role, Content: message.Content})
	}
	return result, nil
}
