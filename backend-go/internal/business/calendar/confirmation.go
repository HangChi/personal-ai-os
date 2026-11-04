package calendar

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
	"strings"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/platform/api"
)

type deletePayload struct {
	IDs    []string `json:"eventIds"`
	Titles []string `json:"titles"`
}

func (*Events) Actions() []confirmation.Action {
	return []confirmation.Action{confirmation.DeleteCalendarEvent}
}

func (s *Events) Execute(ctx context.Context, action confirmation.Action, raw string) (int, error) {
	if action != confirmation.DeleteCalendarEvent {
		return 0, errors.New("日程确认处理器收到未知动作")
	}
	var payload deletePayload
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return 0, err
	}
	ids := slices.Clone(payload.IDs)
	slices.Sort(ids)
	var affected int
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		for _, id := range slices.Compact(ids) {
			_, err := deleteEvent(ctx, tx, id)
			if errors.Is(err, sql.ErrNoRows) {
				continue
			}
			if err != nil {
				return err
			}
			affected++
		}
		if affected == 0 {
			return confirmation.ErrTargetMissing
		}
		return nil
	})
	return affected, err
}

func (s *Events) RequestDelete(ctx context.Context, confirms *confirmation.Service, ids []string, conversation string) (confirmation.DTO, error) {
	if len(ids) == 0 {
		return confirmation.DTO{}, validation("至少需要一个日程标识")
	}
	if len(ids) > 50 {
		return confirmation.DTO{}, validation("一次最多处理 50 个日程，请拆分后重试")
	}
	payload := deletePayload{}
	seen := make(map[string]bool, len(ids))
	for _, id := range ids {
		if !uuidPattern.MatchString(id) {
			return confirmation.DTO{}, validation("eventIds 不是合法的 UUID")
		}
		id = strings.ToLower(id)
		if seen[id] {
			continue
		}
		seen[id] = true
		event, err := s.Get(ctx, id)
		var problem *api.BusinessError
		if errors.As(err, &problem) && problem.Code() == api.ResourceNotFound {
			continue
		}
		if err != nil {
			return confirmation.DTO{}, err
		}
		payload.IDs = append(payload.IDs, event.ID)
		payload.Titles = append(payload.Titles, event.Title)
	}
	if len(payload.IDs) == 0 {
		return confirmation.DTO{}, validation("指定的日程都不存在，未生成确认事项")
	}
	summary := "删除日程：" + payload.Titles[0]
	if len(payload.IDs) > 1 {
		summary = fmt.Sprintf("删除 %d 个日程：%s", len(payload.IDs), strings.Join(payload.Titles, "、"))
	}
	return confirms.Create(ctx, confirmation.DeleteCalendarEvent, summary, payload, len(payload.IDs), conversation)
}
