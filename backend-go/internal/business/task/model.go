package task

import (
	"strings"
	"time"
	"unicode"
	"unicode/utf16"

	textutil "personal-ai-os/backend-go/internal/platform/text"
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

type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }
func invalid(message string) error       { return &ValidationError{message} }
func javaSpace(r rune) bool {
	return r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || r >= 0x1c && r <= 0x1f || r != 0xa0 && r != 0x2007 && r != 0x202f && (unicode.Is(unicode.Zs, r) || r == 0x2028 || r == 0x2029)
}
func strip(s string) string    { return strings.TrimFunc(s, javaSpace) }
func utf16Length(s string) int { return len(utf16.Encode([]rune(s))) }

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
type DTO struct {
	ID          string     `json:"id"`
	Title       string     `json:"title"`
	Description *string    `json:"description"`
	Status      Status     `json:"status"`
	Priority    Priority   `json:"priority"`
	Tags        []string   `json:"tags"`
	DueAt       *time.Time `json:"dueAt"`
	CompletedAt *time.Time `json:"completedAt"`
	CreatedAt   time.Time  `json:"createdAt"`
	UpdatedAt   time.Time  `json:"updatedAt"`
	Overdue     bool       `json:"overdue"`
}

func (t Task) DTO(now time.Time) DTO {
	return DTO{t.ID, t.Title, t.Description, t.Status, t.Priority, append([]string{}, t.Tags...), t.DueAt, t.CompletedAt, t.CreatedAt, t.UpdatedAt, t.Overdue(now)}
}

func (t *Task) SetStatus(status Status, now time.Time) error {
	if status != Todo && status != Doing && status != Done {
		return invalid("任务状态无效")
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

// UpdateRequest 保留 Java Boolean 的 null/false/true，避免省略字段改变已有幂等摘要。
type UpdateRequest struct {
	Title       *string    `json:"title"`
	Description *string    `json:"description"`
	Priority    *Priority  `json:"priority"`
	DueAt       *time.Time `json:"dueAt"`
	ClearDueAt  *bool      `json:"clearDueAt"`
	Status      *Status    `json:"status"`
	Tags        *[]string  `json:"tags"`
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
	value, err := textutil.RequireNotBlank(&title, "标题", textutil.TitleMaxLength)
	if err != nil {
		return "", invalid(err.Error())
	}
	return value, nil
}
func NormalizeDescription(description *string) (*string, error) {
	return textutil.TrimToNull(description), nil
}
