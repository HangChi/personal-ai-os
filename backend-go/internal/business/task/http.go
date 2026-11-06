package task

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

var keyPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,120}$`)
var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

type HTTP struct {
	Tasks         *Service
	Idempotency   *idempotency.Service
	Confirmations *confirmation.Service
	logger        *slog.Logger
}

func NewHTTP(tasks *Service, idem *idempotency.Service, confirmations *confirmation.Service, logger *slog.Logger) (http.Handler, error) {
	if tasks == nil || idem == nil || confirmations == nil || logger == nil {
		return nil, errors.New("任务HTTP依赖不完整")
	}
	h := &HTTP{tasks, idem, confirmations, logger}
	return web.HandleErrors(h.serve, logger), nil
}
func (h *HTTP) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	web.HandleErrors(h.serve, h.logger).ServeHTTP(w, r)
}
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) error {
	path := r.URL.Path
	if path == "/api/v1/projects" || strings.HasPrefix(path, "/api/v1/projects/") || path == "/api/v1/workspace/runs" {
		return h.workspaceHTTP(w, r)
	}
	if path == "/api/v1/tasks" {
		switch r.Method {
		case http.MethodGet, http.MethodHead:
			return h.list(w, r)
		case http.MethodPost:
			return h.create(w, r)
		default:
			return web.MethodNotSupported(r.Method, []string{"GET", "POST"})
		}
	}
	if path == "/api/v1/tasks/tags" {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		tags, err := h.Tasks.ListTags(r.Context())
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, tags)
	}
	if path == "/api/v1/tasks/stats" {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		stats, err := h.Tasks.Stats(r.Context())
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, stats)
	}
	if strings.HasPrefix(path, "/api/v1/tasks/") {
		parts := strings.Split(strings.TrimPrefix(path, "/api/v1/tasks/"), "/")
		if len(parts) > 2 || !uuidPattern.MatchString(parts[0]) {
			return web.NotFound()
		}
		id := parts[0]
		if len(parts) == 2 {
			if r.Method != http.MethodPost {
				return web.MethodNotSupported(r.Method, []string{"POST"})
			}
			var completed bool
			switch parts[1] {
			case "complete":
				completed = true
			case "reopen":
			default:
				return web.NotFound()
			}
			task, err := h.Tasks.SetCompleted(r.Context(), id, completed)
			if err != nil {
				return taskError(err)
			}
			return respond(w, r, http.StatusOK, task.DTO(time.Now()))
		}
		switch r.Method {
		case http.MethodGet, http.MethodHead:
			task, err := h.Tasks.Get(r.Context(), id)
			if err != nil {
				return taskError(err)
			}
			return respond(w, r, http.StatusOK, task.DTO(time.Now()))
		case http.MethodPatch:
			return h.update(w, r, id)
		case http.MethodDelete:
			return h.delete(w, r, id)
		default:
			return web.MethodNotSupported(r.Method, []string{"GET", "PATCH", "DELETE"})
		}
	}
	if path == "/api/v1/agent/confirmations" {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		status := confirmation.Status(r.URL.Query().Get("status"))
		if status != "" && status != confirmation.Pending && status != confirmation.Confirmed && status != confirmation.Cancelled && status != confirmation.Expired && status != confirmation.Failed {
			return web.InvalidParameter("status")
		}
		items, err := h.Confirmations.List(r.Context(), r.URL.Query().Get("conversationId"), status)
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, items)
	}
	if strings.HasPrefix(path, "/api/v1/agent/confirmations/") {
		parts := strings.Split(strings.TrimPrefix(path, "/api/v1/agent/confirmations/"), "/")
		if len(parts) != 2 || !uuidPattern.MatchString(parts[0]) {
			return web.NotFound()
		}
		if r.Method != http.MethodPost {
			return web.MethodNotSupported(r.Method, []string{"POST"})
		}
		object, err := web.DecodeJSONObject(w, r, 10<<20)
		if err != nil {
			return err
		}
		token, err := web.JSONStringField(object, "token")
		if err != nil {
			return err
		}
		if token == nil || strip(*token) == "" {
			return web.ValidationFailure([]web.Violation{{Field: "token", Message: "确认令牌不能为空"}})
		}
		switch parts[1] {
		case "confirm":
			value, err := h.Confirmations.Confirm(r.Context(), parts[0], *token)
			if err != nil {
				return confirmError(err)
			}
			return respond(w, r, http.StatusOK, value)
		case "cancel":
			if err := h.Confirmations.Cancel(r.Context(), parts[0], *token); err != nil {
				return confirmError(err)
			}
			return respond(w, r, http.StatusOK, nil)
		default:
			return web.NotFound()
		}
	}
	return web.NotFound()
}
func respond(w http.ResponseWriter, r *http.Request, status int, data any) error {
	media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
	if !ok {
		return web.NotAcceptable()
	}
	if r.Method == http.MethodHead {
		w.Header().Set("Content-Type", media)
		w.WriteHeader(status)
		return nil
	}
	return api.WriteControllerMediaJSON(w, status, api.OK(data), media)
}
func taskError(err error) error {
	if e := workspaceError(err); e != nil {
		return e
	}
	var validation *ValidationError
	if errors.As(err, &validation) {
		return api.NewBusinessError(api.ValidationFailed, validation.Message)
	}
	if errors.Is(err, ErrNotFound) {
		return api.NewBusinessError(api.ResourceNotFound, "任务不存在")
	}
	return err
}
func confirmError(err error) error {
	var e *confirmation.Error
	if errors.As(err, &e) {
		return api.NewBusinessError(e.Code, e.Message)
	}
	return err
}
func (h *HTTP) list(w http.ResponseWriter, r *http.Request) error {
	query := r.URL.Query()
	page, size := 0, 20
	if raw := query.Get("page"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			return web.InvalidParameter("page")
		}
		page = parsed
	}
	if raw := query.Get("size"); raw != "" {
		parsed, err := strconv.Atoi(raw)
		if err != nil {
			return web.InvalidParameter("size")
		}
		size = parsed
	}
	var status *Status
	if raw := query.Get("status"); raw != "" {
		v := Status(raw)
		if v != Todo && v != Doing && v != Done {
			return web.InvalidParameter("status")
		}
		status = &v
	}
	q := Query{Status: status, Keyword: query.Get("keyword"), Tag: query.Get("tag"), Page: page, Size: size, ProjectID: query.Get("projectId"), ParentTaskID: query.Get("parentTaskId")}
	for _, id := range []string{q.ProjectID, q.ParentTaskID} {
		if id != "" && !uuidPattern.MatchString(id) {
			return web.InvalidParameter("projectId/parentTaskId")
		}
	}
	if raw := query.Get("incomplete"); raw != "" {
		v, e := strconv.ParseBool(raw)
		if e != nil {
			return web.InvalidParameter("incomplete")
		}
		q.Incomplete = v
	}
	for name, dest := range map[string]**time.Time{"dueFrom": &q.DueFrom, "dueBefore": &q.DueBefore} {
		if raw := query.Get(name); raw != "" {
			v, e := time.Parse(time.RFC3339Nano, raw)
			if e != nil {
				return web.InvalidParameter(name)
			}
			*dest = &v
		}
	}
	result, err := h.Tasks.Search(r.Context(), q)
	if err != nil {
		return err
	}
	items := make([]DTO, 0, len(result.Items))
	now := time.Now()
	for _, t := range result.Items {
		items = append(items, t.DTO(now))
	}
	return respond(w, r, http.StatusOK, api.PageOfResult(items, result.Page, result.Size, result.Total))
}
func (h *HTTP) create(w http.ResponseWriter, r *http.Request) error {
	var body CreateRequest
	if err := decodeTaskJSON(w, r, &body, []string{"title", "description", "priority", "dueAt", "tags", "projectId", "parentTaskId", "recurrenceType", "recurrenceTimezone"}); err != nil {
		return err
	}
	value, err := h.idempotent(r, "POST /api/v1/tasks", body, func(ctx context.Context) (any, error) {
		t, e := h.Tasks.Create(ctx, body)
		if e != nil {
			return nil, taskError(e)
		}
		return t.DTO(time.Now()), nil
	})
	if err != nil {
		return err
	}
	return respond(w, r, http.StatusCreated, value)
}
func (h *HTTP) update(w http.ResponseWriter, r *http.Request, id string) error {
	var body UpdateRequest
	if err := decodeTaskJSON(w, r, &body, []string{"title", "description", "priority", "dueAt", "clearDueAt", "status", "tags", "projectId", "parentTaskId", "recurrenceType", "recurrenceTimezone", "clearProject", "clearParent"}); err != nil {
		return err
	}
	value, err := h.idempotent(r, "PATCH /api/v1/tasks/"+id, body, func(ctx context.Context) (any, error) {
		t, e := h.Tasks.Update(ctx, id, body)
		if e != nil {
			return nil, taskError(e)
		}
		return t.DTO(time.Now()), nil
	})
	if err != nil {
		return err
	}
	return respond(w, r, http.StatusOK, value)
}
func decodeTaskJSON(w http.ResponseWriter, r *http.Request, target any, names []string) error {
	object, err := web.DecodeJSONObject(w, r, 10<<20)
	if err != nil {
		return err
	}
	allowed := make(map[string]bool, len(names))
	for _, name := range names {
		allowed[name] = true
	}
	filtered := make(map[string]json.RawMessage)
	for name, raw := range object {
		if allowed[name] {
			filtered[name] = raw
		}
	}
	for _, name := range []string{"title", "description"} {
		if !allowed[name] {
			continue
		}
		if _, exists := filtered[name]; exists {
			value, e := web.JSONStringField(object, name)
			if e != nil {
				return e
			}
			if value == nil {
				filtered[name] = json.RawMessage("null")
			} else {
				limit := 200
				if name == "description" {
					limit = 2000
				}
				if utf16Length(*value) > limit {
					return web.ValidationFailure([]web.Violation{{Field: name, Message: "长度不能超过 " + strconv.Itoa(limit)}})
				}
				filtered[name], e = json.Marshal(*value)
				if e != nil {
					return e
				}
			}
		}
	}
	data, err := json.Marshal(filtered)
	if err != nil {
		return err
	}
	if err := json.Unmarshal(data, target); err != nil {
		return web.InvalidBody(err)
	}
	return nil
}
func (h *HTTP) delete(w http.ResponseWriter, r *http.Request, id string) error {
	value, err := h.idempotent(r, "DELETE /api/v1/tasks/"+id, id, func(ctx context.Context) (any, error) {
		if e := h.Tasks.Delete(ctx, id); e != nil {
			return nil, taskError(e)
		}
		return struct {
			Deleted bool `json:"deleted"`
		}{true}, nil
	})
	if err != nil {
		return err
	}
	return respond(w, r, http.StatusOK, value)
}
func (h *HTTP) idempotent(r *http.Request, operation string, body any, action func(context.Context) (any, error)) (any, error) {
	raw := r.Header.Get("Idempotency-Key")
	if strings.TrimSpace(raw) == "" {
		return action(r.Context())
	}
	key := strings.TrimSpace(raw)
	if !keyPattern.MatchString(key) {
		return nil, api.NewBusinessError(api.ValidationFailed, "幂等键格式非法：需为 8~120 位的字母、数字或 . _ : -")
	}
	digest, err := idempotency.Digest(body)
	if err != nil {
		return nil, err
	}
	result, err := h.Idempotency.Execute(r.Context(), idempotency.Request{Key: key, Source: idempotency.SourceHTTP, Operation: operation, Digest: digest}, func(ctx context.Context) (idempotency.Result, error) {
		value, e := action(ctx)
		if e != nil {
			return idempotency.Result{}, e
		}
		payload, e := json.Marshal(value)
		if e != nil {
			return idempotency.Result{}, e
		}
		text := string(payload)
		return idempotency.NewResult(&text, idempotency.Success), nil
	})
	if err != nil {
		var conflict *idempotency.ConflictError
		if errors.As(err, &conflict) {
			code := api.BusinessConflict
			if conflict.Kind == idempotency.ErrDigestConflict {
				code = api.IdempotencyKeyConflict
			}
			return nil, api.NewBusinessError(code, conflict.Error())
		}
		return nil, err
	}
	if result.ResponsePayload == nil {
		return nil, api.NewBusinessError(api.BusinessConflict, "同一请求正在处理中，请稍后重试")
	}
	var value any
	if err := json.Unmarshal([]byte(*result.ResponsePayload), &value); err != nil {
		return nil, err
	}
	return value, nil
}
