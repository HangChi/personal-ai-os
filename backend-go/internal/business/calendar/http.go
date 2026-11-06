package calendar

import (
	"encoding/json"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

const eventPath = "/api/v1/calendar/events"

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func OwnsEventPath(path string) bool {
	return path == eventPath || strings.HasPrefix(path, eventPath+"/")
}

func (s *Events) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		if r.URL.Path == eventPath+"/ics" {
			return s.exportICS(w, r)
		}
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		var data any
		var status int
		var err error
		if OwnsReminderPath(r.URL.Path) {
			data, status, err = s.serveReminders(r)
		} else {
			data, status, err = s.serve(w, r)
		}
		if err != nil {
			return err
		}
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", media)
			w.WriteHeader(status)
			return nil
		}
		return api.WriteControllerMediaJSON(w, status, api.OK(data), media)
	}, logger)
}

func (s *Events) serve(w http.ResponseWriter, r *http.Request) (any, int, error) {
	if r.URL.Path == eventPath+"/ics/preview" || r.URL.Path == eventPath+"/ics/import" || r.URL.Path == eventPath+"/conflicts" {
		return s.serveExchange(w, r)
	}
	ctx := r.Context()
	if r.URL.Path == eventPath {
		switch r.Method {
		case http.MethodGet, http.MethodHead:
			query := r.URL.Query()
			from, err := rangeTime(query.Get("from"), "from")
			if err != nil {
				return nil, 0, err
			}
			to, err := rangeTime(query.Get("to"), "to")
			if err != nil {
				return nil, 0, err
			}
			page, size := 0, 100
			for _, field := range []struct {
				key    string
				target *int
			}{{"page", &page}, {"size", &size}} {
				if raw := query.Get(field.key); raw != "" {
					value, err := strconv.ParseInt(raw, 10, 32)
					if err != nil {
						return nil, 0, web.InvalidParameter(field.key)
					}
					*field.target = int(value)
				}
			}
			value, err := s.Search(ctx, from, to, page, size)
			return value, http.StatusOK, err
		case http.MethodPost:
			request, err := eventBody(w, r)
			if err != nil {
				return nil, 0, err
			}
			value, err := s.Create(ctx, request)
			return value, http.StatusCreated, err
		default:
			return nil, 0, web.MethodNotSupported(r.Method, []string{"POST", "GET"})
		}
	}
	id := strings.TrimPrefix(r.URL.Path, eventPath+"/")
	if id == "" || strings.Contains(id, "/") {
		return nil, 0, web.NotFound()
	}
	id, valid := api.ParseJavaUUID(strings.TrimSpace(id))
	if !valid {
		return nil, 0, web.InvalidParameter("id")
	}
	switch r.Method {
	case http.MethodGet, http.MethodHead:
		value, err := s.Get(ctx, id)
		return value, http.StatusOK, err
	case http.MethodPatch:
		request, err := eventBody(w, r)
		if err != nil {
			return nil, 0, err
		}
		value, err := s.Update(ctx, id, request)
		return value, http.StatusOK, err
	case http.MethodDelete:
		removed, err := s.Delete(ctx, id)
		return map[string]any{"deleted": true, "deletedReminders": removed}, http.StatusOK, err
	default:
		return nil, 0, web.MethodNotSupported(r.Method, []string{"GET", "PATCH", "DELETE"})
	}
}

func eventBody(w http.ResponseWriter, r *http.Request) (EventRequest, error) {
	var request EventRequest
	if r.ContentLength == 0 {
		return request, web.InvalidBody(nil)
	}
	object, err := web.DecodeJSONObject(w, r, 10<<20)
	if err != nil {
		return request, err
	}
	for _, field := range []struct {
		key    string
		target **string
	}{
		{"title", &request.Title}, {"description", &request.Description}, {"location", &request.Location},
	} {
		*field.target, err = web.JSONStringField(object, field.key)
		if err != nil {
			return request, err
		}
	}
	for _, field := range []struct {
		key    string
		target any
	}{
		{"startsAt", &request.StartsAt}, {"endsAt", &request.EndsAt},
		{"recurrenceType", &request.RecurrenceType}, {"recurrenceUntil", &request.RecurrenceUntil},
	} {
		if raw, ok := object[field.key]; ok {
			if err := json.Unmarshal(raw, field.target); err != nil {
				return request, web.InvalidBody(err)
			}
		}
	}
	request.AllDay, err = web.JSONBoolField(object, "allDay")
	if err != nil {
		return request, err
	}
	if r.Method == http.MethodPatch {
		clear, err := web.JSONBoolField(object, "clearRecurrenceUntil")
		if err != nil {
			return request, err
		}
		request.ClearRecurrenceUntil = clear != nil && *clear
	}
	request.Lead, err = web.JSONIntField(object, "reminderLeadMinutes")
	if err != nil {
		return request, err
	}
	if request.RecurrenceType != nil {
		switch *request.RecurrenceType {
		case None, Daily, Weekly, Weekdays, Monthly:
		default:
			return request, web.InvalidBody(nil)
		}
	}
	return request, nil
}

func rangeTime(raw, field string) (*time.Time, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, nil
	}
	value, err := time.Parse(time.RFC3339Nano, raw)
	if err != nil {
		return nil, validation("参数 " + field + " 不是合法的时间，请使用 ISO-8601（如 2026-10-15T09:00:00Z）")
	}
	return &value, nil
}
