package calendar

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

type Reminder struct {
	ID           string     `json:"id"`
	SourceType   string     `json:"sourceType"`
	SourceID     string     `json:"sourceId"`
	Title        string     `json:"title"`
	OccurrenceAt time.Time  `json:"occurrenceAt"`
	RemindAt     time.Time  `json:"remindAt"`
	Status       string     `json:"status"`
	ReadAt       *time.Time `json:"readAt"`
}

type ScanResult struct {
	WindowStart time.Time `json:"windowStart"`
	WindowEnd   time.Time `json:"windowEnd"`
	ScannedAt   time.Time `json:"scannedAt"`
	Created     int64     `json:"created"`
	Skipped     int64     `json:"skipped"`
	Pruned      int64     `json:"pruned"`
}

const reminderColumns = "id::text,source_type,source_id::text,title,occurrence_at,remind_at,status,read_at"

func scanReminder(row interface{ Scan(...any) error }) (Reminder, error) {
	var reminder Reminder
	err := row.Scan(&reminder.ID, &reminder.SourceType, &reminder.SourceID, &reminder.Title,
		&reminder.OccurrenceAt, &reminder.RemindAt, &reminder.Status, &reminder.ReadAt)
	reminder.OccurrenceAt, reminder.RemindAt = reminder.OccurrenceAt.UTC(), reminder.RemindAt.UTC()
	if reminder.ReadAt != nil {
		value := reminder.ReadAt.UTC()
		reminder.ReadAt = &value
	}
	return reminder, err
}

func (s *Events) Reminders(ctx context.Context, status string, dueOnly bool, limit int, now time.Time) ([]Reminder, error) {
	if status == "" {
		status = "PENDING"
	}
	if limit <= 0 {
		limit = s.settings.DefaultQueryLimit
	} else {
		limit = min(limit, s.settings.MaxQueryLimit)
	}
	result := []Reminder{}
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, "SELECT "+reminderColumns+` FROM reminder
			WHERE status=$1 AND (NOT $2::boolean OR remind_at<=$3) ORDER BY remind_at LIMIT $4`,
			status, dueOnly && status == "PENDING", now, limit)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			reminder, err := scanReminder(rows)
			if err != nil {
				return err
			}
			result = append(result, reminder)
		}
		return rows.Err()
	})
	return result, err
}

func (s *Events) MarkRead(ctx context.Context, id string, now time.Time) (Reminder, error) {
	var reminder Reminder
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		var err error
		reminder, err = scanReminder(conn.QueryRowContext(ctx, `UPDATE reminder
			SET read_at=CASE WHEN status='READ' THEN read_at ELSE $2 END,status='READ'
			WHERE id=$1 RETURNING `+reminderColumns, id, now))
		if errors.Is(err, sql.ErrNoRows) {
			return api.NewBusinessError(api.ResourceNotFound, "提醒不存在："+id)
		}
		return err
	})
	return reminder, err
}

func (s *Events) Scan(ctx context.Context, now time.Time) (ScanResult, error) {
	now = now.UTC()
	start, end := now.Add(-s.settings.ScanLookback), now.Add(s.settings.ScanLookahead)
	eventEnd := now.Add(max(s.settings.ScanLookahead, maxLead*time.Minute))
	result := ScanResult{WindowStart: start, WindowEnd: end, ScannedAt: now}
	err := s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		// Serialize sweeps to avoid replicas pruning and rebuilding the same window simultaneously.
		if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(346012304, 503)`); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, "SELECT "+eventColumns+` FROM calendar_event
			WHERE starts_at<=$2 AND (recurrence_type<>'NONE' OR starts_at>=$1)
			  AND (recurrence_until IS NULL OR recurrence_until>=$1) ORDER BY id FOR SHARE`, start, eventEnd)
		if err != nil {
			return err
		}
		var events []Event
		for rows.Next() {
			event, err := scanEvent(rows)
			if err != nil {
				rows.Close()
				return err
			}
			events = append(events, event)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		taskRows, err := tx.QueryContext(ctx, `SELECT id::text,title,due_at FROM task
			WHERE status<>'DONE' AND due_at BETWEEN $1 AND $2 ORDER BY id FOR SHARE`, start, end)
		if err != nil {
			return err
		}
		type dueTask struct {
			id, title string
			due       time.Time
		}
		var tasks []dueTask
		for taskRows.Next() {
			var task dueTask
			if err := taskRows.Scan(&task.id, &task.title, &task.due); err != nil {
				taskRows.Close()
				return err
			}
			tasks = append(tasks, task)
		}
		taskRows.Close()
		if err := taskRows.Err(); err != nil {
			return err
		}
		pruned, err := tx.ExecContext(ctx, `DELETE FROM reminder r WHERE
			(r.source_type='TASK' AND r.status='PENDING' AND NOT EXISTS (
				SELECT 1 FROM task t WHERE t.id=r.source_id AND t.status<>'DONE' AND t.due_at=r.occurrence_at))
			OR (r.remind_at<=$1 AND ((r.source_type='TASK' AND NOT EXISTS (SELECT 1 FROM task t WHERE t.id=r.source_id))
				OR (r.source_type='CALENDAR_EVENT' AND NOT EXISTS (SELECT 1 FROM calendar_event e WHERE e.id=r.source_id))))`, end)
		if err != nil {
			return err
		}
		result.Pruned, err = pruned.RowsAffected()
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE reminder r SET title=t.title FROM task t
			WHERE r.source_type='TASK' AND r.status='PENDING' AND r.source_id=t.id AND r.title IS DISTINCT FROM t.title`); err != nil {
			return err
		}
		insert := func(source, id, title string, occurrence time.Time, lead time.Duration) error {
			change, err := tx.ExecContext(ctx, `INSERT INTO reminder(source_type,source_id,title,occurrence_at,remind_at)
				VALUES($1,$2,$3,$4,$5) ON CONFLICT(source_type,source_id,occurrence_at) DO NOTHING`,
				source, id, title, occurrence, occurrence.Add(-lead))
			if err != nil {
				return err
			}
			count, err := change.RowsAffected()
			result.Created += count
			result.Skipped += 1 - count
			return err
		}
		for _, event := range events {
			for _, at := range Occurrences(event.RecurrenceType, event.StartsAt, event.RecurrenceUntil, start, eventEnd, s.zone) {
				if err := insert("CALENDAR_EVENT", event.ID, event.Title, at, time.Duration(event.Lead)*time.Minute); err != nil {
					return err
				}
			}
		}
		for _, task := range tasks {
			if err := insert("TASK", task.id, task.title, task.due, s.settings.DefaultReminderLead.Truncate(time.Second)); err != nil {
				return err
			}
		}
		return nil
	})
	return result, err
}
