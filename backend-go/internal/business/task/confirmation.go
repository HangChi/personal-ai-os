package task

import (
	"context"
	"encoding/json"
	"errors"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/confirmation"
)

type ConfirmationHandler struct{ tasks *Service }

func NewConfirmationHandler(tasks *Service) (*ConfirmationHandler, error) {
	if tasks == nil {
		return nil, errors.New("任务确认处理器缺少任务服务")
	}
	return &ConfirmationHandler{tasks}, nil
}
func (*ConfirmationHandler) Actions() []confirmation.Action {
	return []confirmation.Action{confirmation.DeleteTask, confirmation.ScheduleTask, confirmation.SetPriority}
}
func (h *ConfirmationHandler) Execute(ctx context.Context, action confirmation.Action, payload string) (int, error) {
	switch action {
	case confirmation.DeleteTask:
		var p struct {
			TaskIDs []string `json:"taskIds"`
		}
		if err := json.Unmarshal([]byte(payload), &p); err != nil {
			return 0, err
		}
		count, err := h.tasks.DeleteAll(ctx, p.TaskIDs)
		if err != nil {
			return 0, err
		}
		if count == 0 {
			return 0, confirmation.ErrTargetMissing
		}
		return count, nil
	case confirmation.ScheduleTask:
		var p struct {
			TaskID string     `json:"taskId"`
			DueAt  *time.Time `json:"dueAt"`
		}
		if err := json.Unmarshal([]byte(payload), &p); err != nil {
			return 0, err
		}
		_, err := h.tasks.Reschedule(ctx, p.TaskID, p.DueAt)
		if errors.Is(err, ErrNotFound) {
			return 0, confirmation.ErrTargetMissing
		}
		return 1, err
	case confirmation.SetPriority:
		var p struct {
			TaskID   string   `json:"taskId"`
			Priority Priority `json:"priority"`
		}
		if err := json.Unmarshal([]byte(payload), &p); err != nil {
			return 0, err
		}
		_, err := h.tasks.ChangePriority(ctx, p.TaskID, p.Priority)
		if errors.Is(err, ErrNotFound) {
			return 0, confirmation.ErrTargetMissing
		}
		return 1, err
	}
	return 0, errors.New("任务确认处理器收到未知动作")
}

type ConfirmationRequests struct {
	tasks   *Service
	service *confirmation.Service
}

func NewConfirmationRequests(tasks *Service, service *confirmation.Service) (*ConfirmationRequests, error) {
	if tasks == nil || service == nil {
		return nil, errors.New("任务确认请求缺少依赖")
	}
	return &ConfirmationRequests{tasks, service}, nil
}
func (r *ConfirmationRequests) Delete(ctx context.Context, ids []string, conversation string) (confirmation.DTO, error) {
	if len(ids) == 0 || len(ids) > 50 {
		return confirmation.DTO{}, errors.New("任务数无效")
	}
	titles := make([]string, 0, len(ids))
	for _, id := range ids {
		task, err := r.tasks.Get(ctx, id)
		if err != nil {
			return confirmation.DTO{}, err
		}
		titles = append(titles, task.Title)
	}
	summary := "删除任务：" + titles[0]
	if len(titles) > 1 {
		summary = "删除 " + itoa(len(titles)) + " 个任务：" + strings.Join(titles, "、")
	}
	return r.service.Create(ctx, confirmation.DeleteTask, summary, struct {
		TaskIDs []string `json:"taskIds"`
		Titles  []string `json:"titles"`
	}{ids, titles}, len(ids), conversation)
}
func (r *ConfirmationRequests) Schedule(ctx context.Context, id string, due *time.Time, conversation string) (confirmation.DTO, error) {
	task, err := r.tasks.Get(ctx, id)
	if err != nil {
		return confirmation.DTO{}, err
	}
	summary := "清除任务「" + task.Title + "」的截止时间"
	if due != nil {
		summary = "把任务「" + task.Title + "」的截止时间改为 " + due.UTC().Format(time.RFC3339Nano)
	}
	return r.service.Create(ctx, confirmation.ScheduleTask, summary, struct {
		TaskID string     `json:"taskId"`
		Title  string     `json:"title"`
		DueAt  *time.Time `json:"dueAt"`
	}{id, task.Title, due}, 1, conversation)
}
func (r *ConfirmationRequests) Priority(ctx context.Context, id string, p Priority, conversation string) (confirmation.DTO, error) {
	task, err := r.tasks.Get(ctx, id)
	if err != nil {
		return confirmation.DTO{}, err
	}
	return r.service.Create(ctx, confirmation.SetPriority, "把任务「"+task.Title+"」的优先级改为 "+string(p), struct {
		TaskID   string   `json:"taskId"`
		Title    string   `json:"title"`
		Priority Priority `json:"priority"`
	}{id, task.Title, p}, 1, conversation)
}
func itoa(n int) string { return strconv.Itoa(n) }
