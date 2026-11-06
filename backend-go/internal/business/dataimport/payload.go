package dataimport

import (
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/platform/api"
	"strings"
	"time"
)

type UUID string

func (u *UUID) UnmarshalJSON(raw []byte) error {
	var s string
	if e := json.Unmarshal(raw, &s); e != nil {
		return e
	}
	id, ok := parseUUID(s)
	if !ok {
		return errors.New("UUID格式非法")
	}
	*u = UUID(id)
	return nil
}

func parseUUID(s string) (string, bool) {
	s = strings.TrimFunc(s, func(r rune) bool { return r <= 0x20 })
	id, ok := api.ParseJavaUUID(s)
	// Jackson's UUID binding is stricter than UUID.fromString used by URL parameters.
	return id, ok && len(s) == 36 && strings.EqualFold(id, s)
}

type Payload struct {
	Projects       []*Project                  `json:"projects"`
	FormatVersion  *string                     `json:"formatVersion"`
	Sessions       []*Session                  `json:"sessions"`
	Tasks          []*Task                     `json:"tasks"`
	CalendarEvents []*Calendar                 `json:"calendarEvents"`
	Memories       []*Memory                   `json:"memories"`
	Documents      []*knowledge.ImportDocument `json:"knowledgeDocuments"`
}
type Session struct {
	Session  *SessionMeta `json:"session"`
	Messages []*Message   `json:"messages"`
}
type SessionMeta struct {
	ReadOnlyReport bool       `json:"readOnlyReport"`
	ConversationID string     `json:"conversationId"`
	Title          string     `json:"title"`
	CreatedAt      *time.Time `json:"createdAt"`
	UpdatedAt      *time.Time `json:"updatedAt"`
}
type Message struct {
	Role       string     `json:"role"`
	Content    *string    `json:"content"`
	SequenceNo *int       `json:"sequenceNo"`
	CreatedAt  *time.Time `json:"createdAt"`
}
type Project struct {
	ID          *UUID      `json:"id"`
	Name        string     `json:"name"`
	Description *string    `json:"description"`
	Archived    bool       `json:"archived"`
	CreatedAt   *time.Time `json:"createdAt"`
	UpdatedAt   *time.Time `json:"updatedAt"`
}
type Task struct {
	ProjectID           *UUID      `json:"projectId"`
	ParentTaskID        *UUID      `json:"parentTaskId"`
	RecurrenceType      *string    `json:"recurrenceType"`
	RecurrenceTimezone  *string    `json:"recurrenceTimezone"`
	RecurrenceAnchorDay *int       `json:"recurrenceAnchorDay"`
	RecurrenceGenerated bool       `json:"recurrenceGenerated"`
	ID                  *UUID      `json:"id"`
	Title               string     `json:"title"`
	Description         *string    `json:"description"`
	Status              string     `json:"status"`
	Priority            string     `json:"priority"`
	Tags                []string   `json:"tags"`
	DueAt               *time.Time `json:"dueAt"`
	CompletedAt         *time.Time `json:"completedAt"`
	CreatedAt           *time.Time `json:"createdAt"`
	UpdatedAt           *time.Time `json:"updatedAt"`
	PreviousStatus      string     `json:"previousStatus"`
}
type Calendar struct {
	ID              *UUID      `json:"id"`
	Title           string     `json:"title"`
	Description     *string    `json:"description"`
	Location        *string    `json:"location"`
	AllDay          bool       `json:"allDay"`
	StartsAt        *time.Time `json:"startsAt"`
	EndsAt          *time.Time `json:"endsAt"`
	RecurrenceType  string     `json:"recurrenceType"`
	RecurrenceUntil *time.Time `json:"recurrenceUntil"`
	Lead            *int       `json:"reminderLeadMinutes"`
	CreatedAt       *time.Time `json:"createdAt"`
}
type Memory struct {
	ID           *UUID      `json:"id"`
	Content      string     `json:"content"`
	Source       string     `json:"source"`
	CreatedAt    *time.Time `json:"createdAt"`
	UpdatedAt    *time.Time `json:"updatedAt"`
	Pinned       bool       `json:"pinned"`
	SupersededBy *UUID      `json:"supersededBy"`
	HitCount     int        `json:"hitCount"`
	LastUsedAt   *time.Time `json:"lastUsedAt"`
}
