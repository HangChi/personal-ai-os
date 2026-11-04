package dataimport

import (
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/platform/api"
	"time"
)

type UUID string

func (u *UUID) UnmarshalJSON(raw []byte) error {
	var s string
	if e := json.Unmarshal(raw, &s); e != nil {
		return e
	}
	id, ok := api.ParseJavaUUID(s)
	if !ok {
		return errors.New("UUID格式非法")
	}
	*u = UUID(id)
	return nil
}

type Payload struct {
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
type Task struct {
	ID             *UUID      `json:"id"`
	Title          string     `json:"title"`
	Description    *string    `json:"description"`
	Status         string     `json:"status"`
	Priority       string     `json:"priority"`
	Tags           []string   `json:"tags"`
	DueAt          *time.Time `json:"dueAt"`
	CompletedAt    *time.Time `json:"completedAt"`
	CreatedAt      *time.Time `json:"createdAt"`
	UpdatedAt      *time.Time `json:"updatedAt"`
	PreviousStatus string     `json:"previousStatus"`
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
