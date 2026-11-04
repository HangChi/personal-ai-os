package calendar

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

type Event struct {
	ID              string     `json:"id"`
	Title           string     `json:"title"`
	Description     *string    `json:"description"`
	Location        *string    `json:"location"`
	AllDay          bool       `json:"allDay"`
	StartsAt        time.Time  `json:"startsAt"`
	EndsAt          time.Time  `json:"endsAt"`
	RecurrenceType  Recurrence `json:"recurrenceType"`
	RecurrenceUntil *time.Time `json:"recurrenceUntil"`
	Lead            int        `json:"reminderLeadMinutes"`
	CreatedAt       time.Time  `json:"createdAt"`
}

type EventRequest struct {
	Title                *string     `json:"title"`
	Description          *string     `json:"description"`
	Location             *string     `json:"location"`
	AllDay               *bool       `json:"allDay"`
	StartsAt             *time.Time  `json:"startsAt"`
	EndsAt               *time.Time  `json:"endsAt"`
	RecurrenceType       *Recurrence `json:"recurrenceType"`
	RecurrenceUntil      *time.Time  `json:"recurrenceUntil"`
	ClearRecurrenceUntil bool        `json:"clearRecurrenceUntil"`
	Lead                 *int        `json:"reminderLeadMinutes"`
}

type Occurrence struct {
	EventID        string     `json:"eventId"`
	Title          string     `json:"title"`
	Location       *string    `json:"location"`
	AllDay         bool       `json:"allDay"`
	StartsAt       time.Time  `json:"startsAt"`
	EndsAt         time.Time  `json:"endsAt"`
	RecurrenceType Recurrence `json:"recurrenceType"`
	Recurring      bool       `json:"recurring"`
	Lead           int        `json:"reminderLeadMinutes"`
}

type Events struct {
	pool     *database.Pool
	settings config.CalendarProperties
	zone     *time.Location
}

func NewEvents(pool *database.Pool, settings config.CalendarProperties, zone *time.Location) *Events {
	return &Events{pool: pool, settings: settings, zone: zone}
}

const eventColumns = "id::text,title,description,location,all_day,starts_at,ends_at,recurrence_type,recurrence_until,reminder_lead_minutes,created_at"
const maxLead = 10080

func scanEvent(row interface{ Scan(...any) error }) (Event, error) {
	var event Event
	err := row.Scan(&event.ID, &event.Title, &event.Description, &event.Location, &event.AllDay,
		&event.StartsAt, &event.EndsAt, &event.RecurrenceType, &event.RecurrenceUntil, &event.Lead, &event.CreatedAt)
	event.StartsAt, event.EndsAt, event.CreatedAt = event.StartsAt.UTC(), event.EndsAt.UTC(), event.CreatedAt.UTC()
	if event.RecurrenceUntil != nil {
		value := event.RecurrenceUntil.UTC()
		event.RecurrenceUntil = &value
	}
	return event, err
}

func validation(message string) error {
	return api.NewBusinessError(api.ValidationFailed, message)
}

func notFound(id string, err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return api.NewBusinessError(api.ResourceNotFound, "日程不存在："+id)
	}
	return err
}

func (s *Events) dayStart(t time.Time) time.Time {
	local := t.In(s.zone)
	return resolveWallTime(wallDate(local), s.zone, 100_000).UTC()
}

func (s *Events) apply(event *Event, request EventRequest, creating bool) error {
	if request.Title != nil {
		event.Title = strings.TrimSpace(*request.Title)
	}
	if event.Title == "" {
		return validation("标题不能为空")
	}
	for _, field := range []struct {
		value *string
		label string
		limit int
	}{
		{request.Title, "标题", 200}, {request.Description, "描述", 2000}, {request.Location, "地点", 200},
	} {
		if field.value != nil && len(utf16.Encode([]rune(*field.value))) > field.limit {
			return validation(fmt.Sprintf("%s长度不能超过 %d", field.label, field.limit))
		}
	}
	if request.Description != nil {
		event.Description = optionalText(*request.Description)
	}
	if request.Location != nil {
		event.Location = optionalText(*request.Location)
	}
	if request.AllDay != nil {
		event.AllDay = *request.AllDay
	}
	if creating && (request.StartsAt == nil || request.EndsAt == nil) {
		return validation("开始时间与结束时间不能为空")
	}
	if (request.StartsAt == nil) != (request.EndsAt == nil) {
		return validation("修改日程时间时必须同时提供开始时间与结束时间")
	}
	if request.StartsAt != nil {
		event.StartsAt, event.EndsAt = request.StartsAt.UTC(), request.EndsAt.UTC()
	}
	if event.AllDay {
		event.StartsAt, event.EndsAt = s.dayStart(event.StartsAt), s.dayStart(event.EndsAt)
		if !event.EndsAt.After(event.StartsAt) {
			event.EndsAt = event.StartsAt.Add(24 * time.Hour)
		}
	}
	if event.EndsAt.Before(event.StartsAt) {
		return validation("结束时间不能早于开始时间")
	}
	if request.RecurrenceType != nil {
		switch *request.RecurrenceType {
		case None, Daily, Weekly, Weekdays, Monthly:
			event.RecurrenceType = *request.RecurrenceType
		default:
			return validation("重复规则非法")
		}
	}
	if request.RecurrenceUntil != nil {
		if request.RecurrenceUntil.Before(event.StartsAt) {
			return validation("重复截止时间不能早于开始时间")
		}
		event.RecurrenceUntil = request.RecurrenceUntil
	} else if request.ClearRecurrenceUntil {
		event.RecurrenceUntil = nil
	}
	if request.Lead != nil {
		if *request.Lead < 0 {
			return validation("提前提醒量不能为负")
		}
		if *request.Lead > maxLead {
			return validation("提前提醒量不能超过 10080 分钟")
		}
		event.Lead = *request.Lead
	}
	return nil
}

func optionalText(value string) *string {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil
	}
	return &value
}

func (s *Events) Create(ctx context.Context, request EventRequest) (Event, error) {
	event := Event{RecurrenceType: None, Lead: int(s.settings.DefaultReminderLead / time.Minute)}
	if err := s.apply(&event, request, true); err != nil {
		return Event{}, err
	}
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		var err error
		event, err = scanEvent(conn.QueryRowContext(ctx, `INSERT INTO calendar_event
			(title,description,location,all_day,starts_at,ends_at,recurrence_type,recurrence_until,reminder_lead_minutes)
			VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING `+eventColumns,
			event.Title, event.Description, event.Location, event.AllDay, event.StartsAt, event.EndsAt,
			event.RecurrenceType, event.RecurrenceUntil, event.Lead))
		return err
	})
	return event, err
}

func (s *Events) Get(ctx context.Context, id string) (Event, error) {
	var event Event
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		var err error
		event, err = scanEvent(conn.QueryRowContext(ctx, "SELECT "+eventColumns+" FROM calendar_event WHERE id=$1", id))
		return notFound(id, err)
	})
	return event, err
}

func (s *Events) Update(ctx context.Context, id string, request EventRequest) (Event, error) {
	var event Event
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var err error
		event, err = scanEvent(tx.QueryRowContext(ctx, "SELECT "+eventColumns+" FROM calendar_event WHERE id=$1 FOR UPDATE", id))
		if err != nil {
			return notFound(id, err)
		}
		if err := s.apply(&event, request, false); err != nil {
			return err
		}
		event, err = scanEvent(tx.QueryRowContext(ctx, `UPDATE calendar_event SET title=$2,description=$3,location=$4,
			all_day=$5,starts_at=$6,ends_at=$7,recurrence_type=$8,recurrence_until=$9,reminder_lead_minutes=$10,updated_at=now()
			WHERE id=$1 RETURNING `+eventColumns, id, event.Title, event.Description, event.Location, event.AllDay,
			event.StartsAt, event.EndsAt, event.RecurrenceType, event.RecurrenceUntil, event.Lead))
		if err != nil {
			return err
		}
		_, err = tx.ExecContext(ctx, "DELETE FROM reminder WHERE source_type='CALENDAR_EVENT' AND source_id=$1 AND status='PENDING'", id)
		return err
	})
	return event, err
}

func (s *Events) Delete(ctx context.Context, id string) (int64, error) {
	var removed int64
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		var err error
		removed, err = deleteEvent(ctx, tx, id)
		return notFound(id, err)
	})
	return removed, err
}

func deleteEvent(ctx context.Context, tx *sql.Tx, id string) (int64, error) {
	if err := tx.QueryRowContext(ctx, "SELECT id::text FROM calendar_event WHERE id=$1 FOR UPDATE", id).Scan(&id); err != nil {
		return 0, err
	}
	result, err := tx.ExecContext(ctx, "DELETE FROM reminder WHERE source_type='CALENDAR_EVENT' AND source_id=$1", id)
	if err != nil {
		return 0, err
	}
	removed, err := result.RowsAffected()
	if err != nil {
		return 0, err
	}
	_, err = tx.ExecContext(ctx, "DELETE FROM calendar_event WHERE id=$1", id)
	return removed, err
}

func (s *Events) Search(ctx context.Context, from, to *time.Time, page, size int) (api.Page[Occurrence], error) {
	start := s.dayStart(time.Now())
	if from != nil {
		start = *from
	}
	end := start.Add(7 * 24 * time.Hour)
	if to != nil {
		end = *to
	}
	if end.Before(start) {
		return api.Page[Occurrence]{}, validation("区间终点不能早于区间起点")
	}
	span := (end.Unix() - start.Unix()) / 86400
	if span > int64(s.settings.MaxRangeDays) {
		return api.Page[Occurrence]{}, validation(fmt.Sprintf("查询区间不能超过 %d 天", s.settings.MaxRangeDays))
	}
	var occurrences []Occurrence
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, "SELECT "+eventColumns+` FROM calendar_event
			WHERE starts_at<=$2 AND ((recurrence_type='NONE' AND starts_at>=$1)
				OR (recurrence_type<>'NONE' AND (recurrence_until IS NULL OR recurrence_until>=$1))) ORDER BY starts_at`, start, end)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			event, err := scanEvent(rows)
			if err != nil {
				return err
			}
			for _, at := range Occurrences(event.RecurrenceType, event.StartsAt, event.RecurrenceUntil, start, end, s.zone) {
				occurrences = append(occurrences, Occurrence{event.ID, event.Title, event.Location, event.AllDay, at,
					at.Add(event.EndsAt.Sub(event.StartsAt).Truncate(time.Millisecond)), event.RecurrenceType, event.RecurrenceType != None, event.Lead})
			}
		}
		return rows.Err()
	})
	sort.SliceStable(occurrences, func(i, j int) bool { return occurrences[i].StartsAt.Before(occurrences[j].StartsAt) })
	if size <= 0 {
		size = s.settings.DefaultQueryLimit
	}
	return api.PageOf(occurrences, page, min(size, s.settings.MaxQueryLimit)), err
}
