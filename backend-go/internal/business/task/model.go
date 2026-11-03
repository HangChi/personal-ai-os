package task

import (
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

type Status string

const (
	Todo  Status = "TODO"
	Doing Status = "DOING"
	Done  Status = "DONE"
)

type Priority string

const (
	Low    Priority = "LOW"
	Medium Priority = "MEDIUM"
	High   Priority = "HIGH"
)

type Task struct {
	ID, Title            string
	Description          *string
	Status               Status
	Priority             Priority
	DueAt, CompletedAt   *time.Time
	PreviousStatus       *Status
	CreatedAt, UpdatedAt time.Time
	Tags                 []string
}

func (t *Task) SetStatus(status Status, now time.Time) error {
	if status != Todo && status != Doing && status != Done {
		return errors.New("任务状态无效")
	}
	if status == Done && t.Status != Done {
		previous := t.Status
		t.PreviousStatus = &previous
	}
	if status != Done {
		t.PreviousStatus = nil
	}
	t.Status = status
	if status == Done {
		t.CompletedAt = &now
	} else {
		t.CompletedAt = nil
	}
	return nil
}
func (t Task) ReopenTarget() Status {
	if t.PreviousStatus == nil {
		return Todo
	}
	return *t.PreviousStatus
}
func (t *Task) SetCompleted(completed bool, now time.Time) error {
	target := Done
	if !completed {
		target = t.ReopenTarget()
	}
	if err := t.SetStatus(target, now); err != nil {
		return err
	}
	t.UpdatedAt = now
	return nil
}
func (t Task) Overdue(now time.Time) bool {
	return t.Status != Done && t.DueAt != nil && t.DueAt.Before(now)
}

type CreateRequest struct {
	Title       string     `json:"title"`
	Description *string    `json:"description"`
	Priority    *Priority  `json:"priority"`
	DueAt       *time.Time `json:"dueAt"`
	Tags        []string   `json:"tags"`
}
type UpdateRequest struct {
	Title, Description *string
	Priority           *Priority
	DueAt              *time.Time
	ClearDueAt         bool
	Status             *Status
	Tags               *[]string
}
type Query struct {
	Status       *Status
	Keyword, Tag string
	Page, Size   int
}

func (q Query) Normalize() Query {
	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 {
		q.Size = 20
	}
	if q.Size > 100 {
		q.Size = 100
	}
	if strings.TrimSpace(q.Tag) == "" {
		q.Tag = ""
	}
	return q
}
func ValidateTitle(title string) (string, error) {
	title = strings.TrimSpace(title)
	if title == "" || utf8.RuneCountInString(title) > 200 {
		return "", errors.New("任务标题不能为空且最多200字")
	}
	return title, nil
}
func NormalizeDescription(description *string) (*string, error) {
	if description == nil {
		return nil, nil
	}
	if utf8.RuneCountInString(*description) > 2000 {
		return nil, errors.New("任务描述最多2000字")
	}
	value := strings.TrimSpace(*description)
	if value == "" {
		return nil, nil
	}
	return &value, nil
}
