package calendar

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"sort"
	"strings"
	"time"

	web "personal-ai-os/backend-go/internal/platform/http"
)

type ICSPreview struct {
	Digest         string     `json:"digest"`
	Events         []ICSEntry `json:"events"`
	DuplicateCount int        `json:"duplicateCount"`
}
type ICSImported struct {
	Created int `json:"created"`
	Skipped int `json:"skipped"`
}
type Conflict struct {
	First  Occurrence `json:"first"`
	Second Occurrence `json:"second"`
}

func icsDigest(s string) string { h := sha256.Sum256([]byte(s)); return hex.EncodeToString(h[:]) }
func icsExists(ctx context.Context, query interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}, uid string) (bool, error) {
	var exists bool
	err := query.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM calendar_ics_identity WHERE uid=$1)", uid).Scan(&exists)
	if err != nil || exists {
		return exists, err
	}
	if strings.HasSuffix(uid, "@personal-ai-os") {
		id := strings.TrimSuffix(uid, "@personal-ai-os")
		if uuidPattern.MatchString(id) {
			err = query.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM calendar_event WHERE id=$1::uuid)", id).Scan(&exists)
		}
	}
	return exists, err
}
func (s *Events) PreviewICS(ctx context.Context, content string) (ICSPreview, error) {
	entries, err := parseICS(content, s.zone)
	if err != nil {
		return ICSPreview{}, err
	}
	result := ICSPreview{Digest: icsDigest(content), Events: entries}
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		for _, entry := range entries {
			exists, e := icsExists(ctx, c, entry.UID)
			if e != nil {
				return e
			}
			if exists {
				result.DuplicateCount++
			}
		}
		return nil
	})
	return result, err
}
func (s *Events) ImportICS(ctx context.Context, content, digest string) (ICSImported, error) {
	entries, err := parseICS(content, s.zone)
	if err != nil {
		return ICSImported{}, err
	}
	if icsDigest(content) != digest {
		return ICSImported{}, validation("文件内容已变化，请重新预览")
	}
	prepared := make([]Event, len(entries))
	for i, entry := range entries {
		event := Event{RecurrenceType: None, Lead: 15}
		if err := s.apply(&event, entry.Event, true); err != nil {
			return ICSImported{}, err
		}
		prepared[i] = event
	}
	result := ICSImported{}
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "SELECT pg_advisory_xact_lock(350044)"); e != nil {
			return e
		}
		for i, entry := range entries {
			exists, e := icsExists(ctx, tx, entry.UID)
			if e != nil {
				return e
			}
			if exists {
				result.Skipped++
				continue
			}
			event := prepared[i]
			var id string
			e = tx.QueryRowContext(ctx, `INSERT INTO calendar_event(title,description,location,all_day,starts_at,ends_at,recurrence_type,recurrence_until,reminder_lead_minutes)
              VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING id::text`, event.Title, event.Description, event.Location, event.AllDay, event.StartsAt, event.EndsAt, event.RecurrenceType, event.RecurrenceUntil, event.Lead).Scan(&id)
			if e != nil {
				return e
			}
			if _, e = tx.ExecContext(ctx, "INSERT INTO calendar_ics_identity(uid,event_id) VALUES($1,$2::uuid)", entry.UID, id); e != nil {
				return e
			}
			result.Created++
		}
		return nil
	})
	return result, err
}
func (s *Events) definitions(ctx context.Context) ([]Event, error) {
	items := []Event{}
	err := s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT "+eventColumns+" FROM calendar_event ORDER BY starts_at,id LIMIT 10001")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			event, e := scanEvent(rows)
			if e != nil {
				return e
			}
			items = append(items, event)
		}
		return rows.Err()
	})
	if len(items) > 10000 {
		return nil, validation("日程超过 10000 条，请缩小数据范围")
	}
	return items, err
}
func (s *Events) conflicts(ctx context.Context, from, to time.Time) (any, error) {
	if !to.After(from) || to.Sub(from) > 31*24*time.Hour {
		return nil, validation("冲突查询须提供最多 31 天的有效区间")
	}
	definitions, e := s.definitions(ctx)
	if e != nil {
		return nil, e
	}
	items := []Occurrence{}
	for _, event := range definitions {
		duration := event.EndsAt.Sub(event.StartsAt)
		if duration <= 0 {
			continue
		}
		if duration > 366*24*time.Hour {
			return nil, validation("存在跨年超长日程，请缩短该日程后查询冲突")
		}
		starts := Occurrences(event.RecurrenceType, event.StartsAt, event.RecurrenceUntil, from.Add(-duration), to, s.zone)
		for _, start := range starts {
			if start.Before(to) && start.Add(duration).After(from) {
				items = append(items, Occurrence{event.ID, event.Title, event.Location, event.AllDay, start, start.Add(duration), event.RecurrenceType, event.RecurrenceType != None, event.Lead})
				if len(items) > 1000 {
					return nil, validation("该窗口日程过多，请缩小查询范围")
				}
			}
		}
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].StartsAt.Before(items[j].StartsAt) })
	pairs := []Conflict{}
	total := 0
	for i := range items {
		for j := i + 1; j < len(items) && items[j].StartsAt.Before(items[i].EndsAt); j++ {
			total++
			if len(pairs) < 100 {
				pairs = append(pairs, Conflict{items[i], items[j]})
			}
		}
	}
	return map[string]any{"items": pairs, "total": total, "truncated": total > len(pairs)}, nil
}
func (s *Events) serveExchange(w http.ResponseWriter, r *http.Request) (any, int, error) {
	path := strings.TrimPrefix(r.URL.Path, eventPath)
	if path == "/conflicts" {
		if r.Method != "GET" && r.Method != "HEAD" {
			return nil, 0, web.MethodNotSupported(r.Method, []string{"GET"})
		}
		from, e := time.Parse(time.RFC3339Nano, r.URL.Query().Get("from"))
		if e != nil {
			return nil, 0, web.InvalidParameter("from")
		}
		to, e := time.Parse(time.RFC3339Nano, r.URL.Query().Get("to"))
		if e != nil {
			return nil, 0, web.InvalidParameter("to")
		}
		value, e := s.conflicts(r.Context(), from, to)
		return value, 200, e
	}
	if r.Method != "POST" {
		return nil, 0, web.MethodNotSupported(r.Method, []string{"POST"})
	}
	raw, e := web.DecodeJSONObject(w, r, 7<<20)
	if e != nil {
		return nil, 0, e
	}
	var request struct {
		Content string `json:"content"`
		Digest  string `json:"digest"`
	}
	encoded, e := json.Marshal(raw)
	if e != nil {
		return nil, 0, e
	}
	if e = json.Unmarshal(encoded, &request); e != nil {
		return nil, 0, web.InvalidBody(e)
	}
	if path == "/ics/preview" {
		value, e := s.PreviewICS(r.Context(), request.Content)
		return value, 200, e
	}
	value, e := s.ImportICS(r.Context(), request.Content, request.Digest)
	return value, 200, e
}
func (s *Events) ExportICS(ctx context.Context) (string, error) {
	items, e := s.definitions(ctx)
	if e != nil {
		return "", e
	}
	sourceUIDs := make(map[string]string)
	e = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, err := c.QueryContext(ctx, "SELECT event_id::text,uid FROM calendar_ics_identity")
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var id, uid string
			if err := rows.Scan(&id, &uid); err != nil {
				return err
			}
			sourceUIDs[id] = uid
		}
		return rows.Err()
	})
	if e != nil {
		return "", e
	}
	return writeICS(items, s.zone, sourceUIDs)
}
func (s *Events) exportICS(w http.ResponseWriter, r *http.Request) error {
	if r.Method != "GET" && r.Method != "HEAD" {
		return web.MethodNotSupported(r.Method, []string{"GET"})
	}
	text, e := s.ExportICS(r.Context())
	if e != nil {
		return e
	}
	w.Header().Set("Content-Type", "text/calendar; charset=UTF-8")
	w.Header().Set("Content-Disposition", `attachment; filename="personal-ai-os.ics"`)
	if r.Method != "HEAD" {
		_, e = w.Write([]byte(text))
	}
	return e
}
