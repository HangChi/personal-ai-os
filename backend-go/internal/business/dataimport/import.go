package dataimport

import (
	"bytes"
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/search"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
	web "personal-ai-os/backend-go/internal/platform/http"
	"reflect"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

const MaxBytes = 20 << 20

var guarded = []string{"project", "knowledge_index_job", "chat_session", "chat_message", "task", "task_tag", "calendar_event", "memory", "knowledge_document", "confirmation", "operation_record"}

type Result struct {
	Sessions       int      `json:"sessions"`
	Messages       int      `json:"messages"`
	Tasks          int      `json:"tasks"`
	CalendarEvents int      `json:"calendarEvents"`
	Memories       int      `json:"memories"`
	Documents      int      `json:"documents"`
	Vectors        int      `json:"vectors"`
	Notes          []string `json:"notes"`
}
type Service struct {
	Pool      *database.Pool
	Knowledge *knowledge.Store
}

func invalid(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }
func Parse(raw []byte) (Payload, error) {
	var p Payload
	if len(raw) > MaxBytes {
		return p, invalid("文件过大，上限 20MB")
	}
	if search.Strip(string(raw)) == "" {
		return p, invalid("请求体为空，请上传导出文件的内容")
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	var rawPayload json.RawMessage
	if e := decoder.Decode(&rawPayload); e != nil {
		return p, invalid("文件不是合法的导出 JSON")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return p, invalid("文件不是合法的导出 JSON")
	}
	bound, e := bind(rawPayload, reflect.TypeFor[Payload]())
	if e != nil || json.Unmarshal(bound, &p) != nil {
		return p, invalid("文件不是合法的导出 JSON")
	}
	// UUID conversion is a binding concern, even for records later skipped by import.
	for _, entry := range p.Documents {
		if entry == nil {
			continue
		}
		if entry.Document != nil && entry.Document.ID != nil {
			id, ok := parseUUID(*entry.Document.ID)
			if !ok {
				return p, invalid("文件不是合法的导出 JSON")
			}
			entry.Document.ID = &id
		}
		if entry.Source != nil && entry.Source.SpaceID != "" {
			id, ok := parseUUID(entry.Source.SpaceID)
			if !ok {
				return p, invalid("文件不是合法的导出 JSON")
			}
			entry.Source.SpaceID = id
		}
	}
	version := "null"
	if p.FormatVersion != nil {
		version = *p.FormatVersion
	}
	if strings.TrimFunc(version, func(r rune) bool { return r <= 0x20 }) != "1" {
		return p, invalid(fmt.Sprintf("不支持的导出格式版本「%s」，当前只能导入 formatVersion=1 的文件", version))
	}
	return p, nil
}
func (s *Service) Import(ctx context.Context, raw []byte) (Result, error) {
	p, e := Parse(raw)
	if e != nil {
		return Result{}, e
	}
	if e = s.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error { return requireEmpty(ctx, tx) }); e != nil {
		return Result{}, e
	}
	// Embedding must never hold table locks or a business write transaction.
	documents, e := s.Knowledge.PrepareImport(ctx, p.Documents)
	if e != nil {
		return Result{}, e
	}
	result := Result{Sessions: len(p.Sessions), Notes: []string{}}
	e = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "LOCK TABLE "+strings.Join(guarded, ",")+" IN SHARE ROW EXCLUSIVE MODE"); e != nil {
			return e
		}
		if e := requireEmpty(ctx, tx); e != nil {
			return e
		}
		var e error
		if result.Messages, e = importSessions(ctx, tx, p.Sessions); e != nil {
			return e
		}
		if e = importProjects(ctx, tx, p.Projects); e != nil {
			return e
		}
		if result.Tasks, e = importTasks(ctx, tx, p.Tasks); e != nil {
			return e
		}
		if e = archiveImportedProjects(ctx, tx, p.Projects); e != nil {
			return e
		}
		if result.CalendarEvents, e = importCalendar(ctx, tx, p.CalendarEvents); e != nil {
			return e
		}
		if result.Memories, e = importMemories(ctx, tx, p.Memories); e != nil {
			return e
		}
		result.Documents, result.Vectors, e = s.Knowledge.ImportPrepared(ctx, tx, documents)
		return e
	})
	if e != nil {
		var constraint *pgconn.PgError
		if errors.As(e, &constraint) && strings.HasPrefix(constraint.Code, "23") {
			return Result{}, web.IntegrityConflict(e)
		}
		return Result{}, e
	}
	if result.Documents > 0 {
		result.Notes = append(result.Notes, "知识库已按分块文本重建向量；原始 PDF/Word/图片不在导出文件里，需要时请重新上传。")
	}
	return result, nil
}
func requireEmpty(ctx context.Context, tx *sql.Tx) error {
	occupied := []string{}
	for _, table := range guarded {
		var count int64
		if e := tx.QueryRowContext(ctx, "SELECT count(*) FROM "+table).Scan(&count); e != nil {
			return e
		}
		if count > 0 {
			occupied = append(occupied, fmt.Sprintf("%s=%d", table, count))
		}
	}
	if len(occupied) > 0 {
		return api.NewBusinessError(api.BusinessConflict, "目标库已有数据（"+strings.Join(occupied, ", ")+"），导入只支持空库。请先切换到空数据库，或清空后重试。")
	}
	return nil
}
func blank(s string) bool { return search.Strip(s) == "" }
func fallbackAt(v *time.Time, f time.Time) time.Time {
	if v != nil {
		return *v
	}
	return f
}
func normalize(v string, allowed []string, fallback string) string {
	v = strings.ToUpper(strings.TrimFunc(v, func(r rune) bool { return r <= 0x20 }))
	for _, a := range allowed {
		if v == a {
			return v
		}
	}
	return fallback
}
func importSessions(ctx context.Context, tx *sql.Tx, sessions []*Session) (int, error) {
	count := 0
	for _, entry := range sessions {
		if entry == nil {
			return 0, invalid("会话记录无效")
		}
		s := entry.Session
		if s == nil || blank(s.ConversationID) {
			continue
		}
		title := s.Title
		if blank(title) {
			title = "导入的会话"
		}
		updated := s.UpdatedAt
		if updated == nil {
			updated = s.CreatedAt
		}
		if _, e := tx.ExecContext(ctx, `INSERT INTO chat_session(conversation_id,title,created_at,updated_at) VALUES($1,$2,$3,$4)`, s.ConversationID, title, s.CreatedAt, updated); e != nil {
			return 0, e
		}
		sequence := 1
		for _, m := range entry.Messages {
			if m == nil {
				return 0, invalid("消息记录无效")
			}
			role := strings.ToUpper(m.Role)
			if (role != "USER" && role != "ASSISTANT") || m.Content == nil {
				continue
			}
			seq := sequence
			if m.SequenceNo != nil && *m.SequenceNo > 0 {
				seq = *m.SequenceNo
			}
			if _, e := tx.ExecContext(ctx, `INSERT INTO chat_message(conversation_id,message_type,content,sequence_no,created_at) VALUES($1,$2,$3,$4,$5)`, s.ConversationID, role, *m.Content, seq, fallbackAt(m.CreatedAt, time.Now().UTC())); e != nil {
				return 0, e
			}
			sequence = seq + 1
			count++
		}
		if s.ReadOnlyReport {
			if _, e := tx.ExecContext(ctx, "UPDATE chat_session SET read_only_report=true WHERE conversation_id=$1", s.ConversationID); e != nil {
				return 0, e
			}
		}
	}
	return count, nil
}
func importTasks(ctx context.Context, tx *sql.Tx, tasks []*Task) (int, error) {
	count := 0
	for _, t := range tasks {
		if t == nil {
			return 0, invalid("任务记录无效")
		}
		if t.ID == nil || blank(t.Title) {
			continue
		}
		created := fallbackAt(t.CreatedAt, time.Now().UTC())
		status := normalize(t.Status, []string{"TODO", "DOING", "DONE"}, "TODO")
		priority := normalize(t.Priority, []string{"LOW", "MEDIUM", "HIGH"}, "MEDIUM")
		var previous *string
		if status == "DONE" {
			v := normalize(t.PreviousStatus, []string{"TODO", "DOING"}, "")
			if v != "" {
				previous = &v
			}
		}
		if _, e := tx.ExecContext(ctx, `INSERT INTO task(id,title,description,status,priority,due_at,completed_at,created_at,updated_at,previous_status,project_id,recurrence_type,recurrence_timezone,recurrence_anchor_day,recurrence_generated) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11::uuid,$12,$13,$14,$15)`, string(*t.ID), t.Title, t.Description, status, priority, t.DueAt, t.CompletedAt, created, fallbackAt(t.UpdatedAt, created), previous, t.ProjectID, defaultString(t.RecurrenceType, "NONE"), defaultString(t.RecurrenceTimezone, "UTC"), t.RecurrenceAnchorDay, t.RecurrenceGenerated); e != nil {
			return 0, e
		}
		index := 0
		for _, tag := range t.Tags {
			if blank(tag) {
				continue
			}
			if _, e := tx.ExecContext(ctx, `INSERT INTO task_tag(task_id,tag_index,tag) VALUES($1,$2,$3)`, string(*t.ID), index, tag); e != nil {
				return 0, e
			}
			index++
		}
		count++
	}
	for _, t := range tasks {
		if t != nil && t.ID != nil && strings.TrimSpace(t.Title) != "" && t.ParentTaskID != nil {
			if _, e := tx.ExecContext(ctx, `UPDATE task SET parent_task_id=$2::uuid WHERE id=$1::uuid`, string(*t.ID), string(*t.ParentTaskID)); e != nil {
				return 0, e
			}
		}
	}
	return count, nil
}
func importCalendar(ctx context.Context, tx *sql.Tx, events []*Calendar) (int, error) {
	count := 0
	for _, v := range events {
		if v == nil {
			return 0, invalid("日程记录无效")
		}
		if v.ID == nil || blank(v.Title) || v.StartsAt == nil {
			continue
		}
		created := fallbackAt(v.CreatedAt, time.Now().UTC())
		lead := 15
		if v.Lead != nil {
			lead = *v.Lead
		}
		recurrence := normalize(v.RecurrenceType, []string{string(calendar.None), string(calendar.Daily), string(calendar.Weekly), string(calendar.Monthly), string(calendar.Weekdays)}, string(calendar.None))
		if _, e := tx.ExecContext(ctx, `INSERT INTO calendar_event(id,title,description,location,all_day,starts_at,ends_at,recurrence_type,recurrence_until,reminder_lead_minutes,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$11)`, string(*v.ID), v.Title, v.Description, v.Location, v.AllDay, *v.StartsAt, fallbackAt(v.EndsAt, *v.StartsAt), recurrence, v.RecurrenceUntil, lead, created); e != nil {
			return 0, e
		}
		count++
	}
	return count, nil
}
func importMemories(ctx context.Context, tx *sql.Tx, memories []*Memory) (int, error) {
	count := 0
	for _, v := range memories {
		if v == nil {
			return 0, invalid("记忆记录无效")
		}
		if v.ID == nil || blank(v.Content) {
			continue
		}
		created := fallbackAt(v.CreatedAt, time.Now().UTC())
		source := v.Source
		if blank(source) {
			source = "MANUAL"
		}
		hash := sha256.Sum256([]byte(v.Content))
		if _, e := tx.ExecContext(ctx, `INSERT INTO memory(id,content,content_hash,created_at,updated_at,source,pinned,hit_count,last_used_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`, string(*v.ID), v.Content, hex.EncodeToString(hash[:]), created, fallbackAt(v.UpdatedAt, created), source, v.Pinned, v.HitCount, v.LastUsedAt); e != nil {
			return 0, e
		}
		count++
	}
	for _, v := range memories {
		if v != nil && v.ID != nil && !blank(v.Content) && v.SupersededBy != nil {
			if _, e := tx.ExecContext(ctx, `UPDATE memory SET superseded_by=$1 WHERE id=$2`, string(*v.SupersededBy), string(*v.ID)); e != nil {
				return 0, e
			}
		}
	}
	return count, nil
}

func defaultString(v *string, fallback string) string {
	if v == nil {
		return fallback
	}
	return *v
}
func importProjects(ctx context.Context, tx *sql.Tx, projects []*Project) error {
	for _, p := range projects {
		if p == nil || p.ID == nil || strings.TrimSpace(p.Name) == "" {
			return invalid("备份项目缺少有效标识或名称")
		}
		created := fallbackAt(p.CreatedAt, time.Now().UTC())
		if _, e := tx.ExecContext(ctx, `INSERT INTO project(id,name,description,created_at,updated_at) VALUES($1::uuid,$2,$3,$4,$5)`, string(*p.ID), strings.TrimSpace(p.Name), p.Description, created, fallbackAt(p.UpdatedAt, created)); e != nil {
			return e
		}
	}
	return nil
}
func archiveImportedProjects(ctx context.Context, tx *sql.Tx, projects []*Project) error {
	for _, p := range projects {
		if p != nil && p.ID != nil && p.Archived {
			if _, e := tx.ExecContext(ctx, `UPDATE project SET archived=true WHERE id=$1::uuid`, string(*p.ID)); e != nil {
				return e
			}
		}
	}
	return nil
}
