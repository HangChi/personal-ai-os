package calendar

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	web "personal-ai-os/backend-go/internal/platform/http"
)

const reminderPath = "/api/v1/reminders"

func OwnsReminderPath(path string) bool {
	return path == reminderPath || strings.HasPrefix(path, reminderPath+"/")
}

func (s *Events) serveReminders(r *http.Request) (any, int, error) {
	ctx, now := r.Context(), time.Now().UTC()
	if r.URL.Path == reminderPath {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return nil, 0, web.MethodNotSupported(r.Method, []string{"GET"})
		}
		query := r.URL.Query()
		status := query.Get("status")
		switch status {
		case "", "PENDING", "READ", "CANCELLED":
		default:
			return nil, 0, web.InvalidParameter("status")
		}
		due := true
		switch strings.ToLower(strings.TrimSpace(query.Get("dueOnly"))) {
		case "", "true", "on", "yes", "1":
		case "false", "off", "no", "0":
			due = false
		default:
			return nil, 0, web.InvalidParameter("dueOnly")
		}
		var limit int64
		if raw := query.Get("limit"); raw != "" {
			value, err := strconv.ParseInt(raw, 10, 32)
			if err != nil {
				return nil, 0, web.InvalidParameter("limit")
			}
			limit = value
		}
		result, err := s.Reminders(ctx, status, due, int(limit), now)
		return result, http.StatusOK, err
	}
	path := strings.TrimPrefix(r.URL.Path, reminderPath+"/")
	if path == "scan" {
		if r.Method != http.MethodPost {
			return nil, 0, web.MethodNotSupported(r.Method, []string{"POST"})
		}
		result, err := s.Scan(ctx, now)
		return result, http.StatusOK, err
	}
	id, action, found := strings.Cut(path, "/")
	if !found || id == "" || action != "read" {
		return nil, 0, web.NotFound()
	}
	if r.Method != http.MethodPost {
		return nil, 0, web.MethodNotSupported(r.Method, []string{"POST"})
	}
	if !uuidPattern.MatchString(id) {
		return nil, 0, web.InvalidParameter("id")
	}
	result, err := s.MarkRead(ctx, id, now)
	return result, http.StatusOK, err
}
