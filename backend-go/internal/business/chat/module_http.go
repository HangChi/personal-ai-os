package chat

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	web "personal-ai-os/backend-go/internal/platform/http"
)

type ModuleHTTP struct {
	module      *Module
	storage     *HTTP
	audit       *agent.Audit
	coordinator *coordination.Coordinator
}

func only(r *http.Request, methods ...string) error {
	for _, m := range methods {
		if r.Method == m || r.Method == "HEAD" && m == "GET" {
			return nil
		}
	}
	return web.MethodNotSupported(r.Method, methods)
}
func (h *ModuleHTTP) serve(w http.ResponseWriter, r *http.Request) error {
	path := r.URL.Path
	m := h.module
	if path == "/api/v1/chat/tools" {
		if e := only(r, "GET"); e != nil {
			return e
		}
		return respond(w, r, 200, m.Engine.Registry.DirectTools())
	}
	if path == "/api/v1/usage" {
		if e := only(r, "GET"); e != nil {
			return e
		}
		days, e := intParameter(r, "days", false)
		if e != nil {
			return e
		}
		v, e := m.Engine.Usage.Stats(r.Context(), days)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if path == "/api/v1/agent/tool-invocations" {
		if e := only(r, "GET"); e != nil {
			return e
		}
		page, e := intParameter(r, "page", false)
		if e != nil {
			return e
		}
		size, e := intParameter(r, "size", false)
		if e != nil {
			return e
		}
		p, z := 0, 20
		if page != nil {
			p = *page
		}
		if size != nil {
			z = *size
		}
		outcome := r.URL.Query().Get("outcome")
		switch outcome {
		case "", "SUCCESS", "REQUIRES_CONFIRMATION", "REJECTED", "DUPLICATE", "FAILED", "IN_PROGRESS":
		default:
			return web.InvalidParameter("outcome")
		}
		v, e := h.audit.Search(r.Context(), r.URL.Query().Get("conversationId"), outcome, p, z)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if strings.HasPrefix(path, "/api/v1/conversations/") && strings.HasSuffix(path, "/plan") {
		if e := only(r, "GET"); e != nil {
			return e
		}
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/api/v1/conversations/"), "/plan")
		if id == "" || strings.Contains(id, "/") {
			return web.NotFound()
		}
		v, e := m.Engine.Plans.Get(r.Context(), id, false)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if path == "/api/v1/chat/context-budget" {
		if e := only(r, "PUT"); e != nil {
			return e
		}
		obj, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		tokens, e := web.JSONIntField(obj, "tokens")
		if e != nil {
			return e
		}
		if tokens == nil || *tokens <= 0 {
			return invalid("预算必须是正整数")
		}
		id, e := web.JSONStringField(obj, "conversationId")
		if e != nil {
			return e
		}
		thinking, e := web.JSONStringField(obj, "thinkingLevel")
		if e != nil {
			return e
		}
		conversation := value(id)
		if strip(conversation) == "" {
			conversation = ""
		}
		if conversation != "" {
			if _, e = m.Service.Require(r.Context(), conversation); e != nil {
				return e
			}
		}
		if e = m.Budget.Set(r.Context(), *tokens); e != nil {
			return e
		}
		v, e := ContextFor(r.Context(), m.Service, m.Budget, m.Engine.Assembler, m.Engine.Usage, conversation, value(thinking))
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if path == "/api/v1/chat/context" {
		if e := only(r, "GET"); e != nil {
			return e
		}
		return h.context(w, r, "")
	}
	if path == "/api/v1/chat/uploads" || strings.HasPrefix(path, "/api/v1/chat/uploads/") {
		return h.upload(w, r)
	}
	const base = "/api/v1/chat/sessions/"
	if strings.HasPrefix(path, base) {
		parts := strings.Split(strings.TrimPrefix(path, base), "/")
		if len(parts) == 2 && parts[1] == "tools" {
			return h.directTool(w, r, parts[0])
		}
		if len(parts) >= 2 {
			id := parts[0]
			switch parts[1] {
			case "context":
				if len(parts) != 2 {
					return web.NotFound()
				}
				if e := only(r, "GET"); e != nil {
					return e
				}
				return h.context(w, r, id)
			case "stream":
				if len(parts) != 2 {
					return web.NotFound()
				}
				if e := only(r, "POST"); e != nil {
					return e
				}
				return h.legacy(w, r, id)
			case "runs":
				return h.runs(w, r, id, parts[2:])
			}
		}
	}
	// 删除与回退共用生成租约，阻止旧执行在历史已修改后继续写入。
	if strings.HasPrefix(path, base) {
		parts := strings.Split(strings.TrimPrefix(path, base), "/")
		if len(parts) == 1 && r.Method == "DELETE" || len(parts) == 2 && parts[1] == "rewind" && r.Method == "POST" {
			lease, e := h.coordinator.Acquire(r.Context(), "chat-session:"+parts[0], 1)
			if e != nil {
				return e
			}
			if lease == nil {
				return agent.Conflict("该会话正在生成回复，请先停止任务再修改历史")
			}
			defer func() {
				ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
				defer cancel()
				_ = lease.Release(ctx)
			}()
			r = r.WithContext(database.WithWriteGuard(r.Context(), lease.Fence))
		}
	}
	return h.storage.serve(w, r)
}

func (h *ModuleHTTP) directTool(w http.ResponseWriter, r *http.Request, conversation string) error {
	if e := only(r, "POST"); e != nil {
		return e
	}
	if _, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"}); !ok {
		return web.NotAcceptable()
	}
	obj, e := web.DecodeJSONObject(w, r, 10<<20)
	if e != nil {
		return e
	}
	tool, e := web.JSONStringField(obj, "tool")
	if e != nil {
		return e
	}
	var arguments map[string]any
	decoder := json.NewDecoder(bytes.NewReader(obj["arguments"]))
	decoder.UseNumber()
	if decoder.Decode(&arguments) != nil || arguments == nil {
		return invalid("arguments 必须是 JSON 对象")
	}
	if _, e = h.module.Service.Require(r.Context(), conversation); e != nil {
		return e
	}
	lease, e := h.coordinator.Acquire(r.Context(), "chat-session:"+conversation, 1)
	if e != nil {
		return e
	}
	if lease == nil {
		return agent.Conflict("该会话正在执行，请先停止任务再运行快捷操作")
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = lease.Release(cleanup)
	}()
	ctx := database.WithWriteGuard(r.Context(), lease.Fence)
	result, e := h.module.Engine.Registry.ExecuteCommand(ctx, conversation, r.Header.Get("Idempotency-Key"), agent.DirectToolRequest{Tool: value(tool), Arguments: arguments}, h.module.Engine.Agent)
	if e != nil {
		return e
	}
	return respond(w, r, 200, result)
}
func (h *ModuleHTTP) context(w http.ResponseWriter, r *http.Request, id string) error {
	m := h.module
	v, e := ContextFor(r.Context(), m.Service, m.Budget, m.Engine.Assembler, m.Engine.Usage, id, r.URL.Query().Get("thinkingLevel"))
	if e != nil {
		return e
	}
	return respond(w, r, 200, v)
}
func decodeStream(obj map[string]json.RawMessage) (v StreamRequest, e error) {
	content, e := web.JSONStringField(obj, "content")
	if e != nil {
		return v, e
	}
	v.Content = value(content)
	if v.UseKnowledge, e = web.JSONBoolField(obj, "useKnowledge"); e != nil {
		return
	}
	if v.TopK, e = web.JSONIntField(obj, "topK"); e != nil {
		return
	}
	if v.ThinkingLevel, e = web.JSONStringField(obj, "thinkingLevel"); e != nil {
		return
	}
	raw := bytes.TrimSpace(obj["attachments"])
	if len(raw) > 0 && !bytes.Equal(raw, []byte("null")) {
		var list []json.RawMessage
		if json.Unmarshal(raw, &list) != nil {
			return v, web.InvalidBody(nil)
		}
		v.Attachments = make([]Attachment, 0, len(list))
		for _, raw := range list {
			if bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
				v.Attachments = append(v.Attachments, Attachment{null: true})
				continue
			}
			var object map[string]json.RawMessage
			if json.Unmarshal(raw, &object) != nil || object == nil {
				return v, web.InvalidBody(nil)
			}
			if bytes.Equal(bytes.TrimSpace(object["size"]), []byte("null")) || len(object["size"]) == 0 {
				object["size"] = json.RawMessage("0")
			}
			a, err := decodeAttachment(object)
			if err != nil {
				return v, web.InvalidBody(err)
			}
			v.Attachments = append(v.Attachments, a)
		}
	}
	return v, nil
}
func streamRequest(w http.ResponseWriter, r *http.Request) (StreamRequest, error) {
	obj, e := web.DecodeJSONObject(w, r, 10<<20)
	if e != nil {
		return StreamRequest{}, e
	}
	request, e := decodeStream(obj)
	if e != nil {
		return request, e
	}
	return request, request.Validate()
}
func turnKey(s string) string {
	s = strip(s)
	if s == "" {
		return agent.UUID()
	}
	if javaLength(s) > 64 {
		return idempotency.SHA256(s)
	}
	return s
}
func sseHeaders(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no")
}
func (h *ModuleHTTP) legacy(w http.ResponseWriter, r *http.Request, id string) error {
	request, e := streamRequest(w, r)
	if e != nil {
		return e
	}
	m := h.module
	p, e := m.Engine.Prepare(r.Context(), id, request)
	if e != nil {
		return e
	}
	lease, e := h.coordinator.Acquire(r.Context(), "chat-session:"+id, 1)
	if e != nil {
		return e
	}
	if lease == nil {
		return agent.Conflict("该会话正在生成回复，请稍候")
	}
	defer func() {
		cleanup, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = lease.Release(cleanup)
	}()
	ctx, cancel := context.WithCancel(database.WithWriteGuard(r.Context(), lease.Fence))
	defer cancel()
	writer := &sseWriter{writer: w}
	sseHeaders(w)
	done := make(chan struct{})
	go func() {
		defer close(done)
		tick := time.NewTicker(h.module.schedule.heartbeat)
		defer tick.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
				if writer.heartbeat() != nil {
					cancel()
					return
				}
			}
		}
	}()
	p.clientContext = ctx
	_ = m.Engine.Run(ctx, p, turnKey(r.Header.Get("Idempotency-Key")), "", func(ctx context.Context) error {
		if !lease.Valid() {
			return coordination.ErrLeaseLost
		}
		return ctx.Err()
	}, func(name string, v any) error { return writer.event(-1, name, v) })
	cancel()
	<-done
	writer.close()
	_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
	return nil
}
func (h *ModuleHTTP) runs(w http.ResponseWriter, r *http.Request, conversation string, parts []string) error {
	s := h.module.Runs
	if len(parts) == 0 {
		if e := only(r, "POST"); e != nil {
			return e
		}
		request, e := streamRequest(w, r)
		if e != nil {
			return e
		}
		v, e := s.Start(r.Context(), conversation, r.Header.Get("Idempotency-Key"), request)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	id := parts[0]
	if id == "" {
		return web.NotFound()
	}
	if len(parts) == 1 {
		if e := only(r, "GET"); e != nil {
			return e
		}
		after, e := cursorParameter(r)
		if e != nil {
			return e
		}
		if id == "latest" {
			id = ""
		}
		v, e := s.Get(r.Context(), conversation, id, after)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if len(parts) == 2 && parts[1] == "events" {
		if e := only(r, "GET"); e != nil {
			return e
		}
		if !eventAcceptable(r.Header.Get("Accept")) {
			return web.NotAcceptable()
		}
		after, e := cursorParameter(r)
		if e != nil {
			return e
		}
		if _, e = s.Get(r.Context(), conversation, id, after); e != nil {
			return e
		}
		return h.events(w, r, conversation, id, after)
	}
	if len(parts) == 2 && parts[1] == "followups" {
		if e := only(r, "POST"); e != nil {
			return e
		}
		obj, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		mode, e := web.JSONStringField(obj, "mode")
		if e != nil {
			return e
		}
		if mode == nil || (*mode != "QUEUE" && *mode != "STEER") {
			return invalid("mode: 必须匹配QUEUE|STEER")
		}
		var nested map[string]json.RawMessage
		if json.Unmarshal(obj["request"], &nested) != nil || nested == nil {
			return invalid("request: 不能为null")
		}
		request, e := decodeStream(nested)
		if e != nil {
			return e
		}
		v, e := s.Followup(r.Context(), conversation, id, r.Header.Get("Idempotency-Key"), *mode, request)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if len(parts) >= 3 && parts[1] == "followups" {
		input := parts[2]
		action := ""
		content := ""
		if len(parts) == 3 {
			switch r.Method {
			case "DELETE":
				action = "cancel"
			case "PATCH":
				action = "edit"
				obj, e := web.DecodeJSONObject(w, r, 10<<20)
				if e != nil {
					return e
				}
				text, e := web.JSONStringField(obj, "content")
				if e != nil {
					return e
				}
				content = value(text)
				if strip(content) == "" || javaLength(content) > 4000 {
					return invalid("content: 消息须为 1～4000 字")
				}
			default:
				return web.MethodNotSupported(r.Method, []string{"DELETE", "PATCH"})
			}
		} else if len(parts) == 4 && parts[3] == "steer" {
			if e := only(r, "POST"); e != nil {
				return e
			}
			action = "steer"
		} else {
			return web.NotFound()
		}
		v, e := s.ChangeFollowup(r.Context(), conversation, id, input, action, content)
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if len(parts) == 4 && parts[1] == "questions" && parts[3] == "answer" {
		if e := only(r, "POST"); e != nil {
			return e
		}
		obj, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return e
		}
		a, e := web.JSONStringField(obj, "answer")
		if e != nil {
			return e
		}
		if strip(value(a)) == "" || javaLength(value(a)) > 4000 {
			return invalid("answer: 回答须为 1～4000 字")
		}
		v, e := s.Answer(r.Context(), conversation, id, parts[2], value(a))
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	if len(parts) == 2 && (parts[1] == "resume" || parts[1] == "stop") || len(parts) == 3 && parts[1] == "confirmations" && parts[2] == "finish" {
		if e := only(r, "POST"); e != nil {
			return e
		}
		if parts[1] == "stop" {
			if e := s.Stop(r.Context(), conversation, id); e != nil {
				return e
			}
			return respond(w, r, 200, nil)
		}
		var v *Run
		var e error
		if parts[1] == "resume" {
			v, e = s.Resume(r.Context(), conversation, id, "")
		} else {
			v, e = s.FinishConfirmations(r.Context(), conversation, id)
		}
		if e != nil {
			return e
		}
		return respond(w, r, 200, v)
	}
	return web.NotFound()
}
func cursorParameter(r *http.Request) (int64, error) {
	s := r.URL.Query().Get("after")
	if strip(s) == "" {
		return 0, nil
	}
	n, e := strconv.ParseInt(strip(s), 10, 64)
	if e != nil {
		return 0, web.InvalidParameter("after")
	}
	return max(0, n), nil
}
func eventAcceptable(s string) bool {
	if s == "" {
		return true
	}
	for _, part := range strings.Split(s, ",") {
		v := strings.TrimSpace(strings.SplitN(part, ";", 2)[0])
		if v == "*/*" || v == "text/*" || strings.EqualFold(v, "text/event-stream") {
			return true
		}
	}
	return false
}
func (h *ModuleHTTP) events(w http.ResponseWriter, r *http.Request, conversation, id string, cursor int64) error {
	s := h.module.Runs
	sseHeaders(w)
	writer := &sseWriter{writer: w}
	defer writer.close()
	defer http.NewResponseController(w).SetWriteDeadline(time.Time{})
	if r.Method == "HEAD" {
		w.WriteHeader(200)
		return nil
	}
	deadline := time.NewTimer(5 * time.Minute)
	defer deadline.Stop()
	heartbeat := time.NewTicker(h.module.schedule.heartbeat)
	defer heartbeat.Stop()
	poll := time.NewTicker(time.Second)
	defer poll.Stop()
	for {
		signal, unsubscribe := s.Watch(id)
		run, e := s.Get(r.Context(), conversation, id, cursor)
		if e != nil {
			unsubscribe()
			return nil
		}
		if len(run.Events) > 0 || run.Status != "RUNNING" {
			if len(run.Events) > 0 {
				cursor = run.Events[len(run.Events)-1].ID
			}
			if writer.event(cursor, "run", run) != nil {
				unsubscribe()
				return nil
			}
		}
		if run.Status != "RUNNING" && len(run.Events) < 200 {
			unsubscribe()
			return nil
		}
		if len(run.Events) == 200 {
			unsubscribe()
			continue
		}
		select {
		case <-r.Context().Done():
			unsubscribe()
			return nil
		case <-s.ctx.Done():
			unsubscribe()
			return nil
		case <-deadline.C:
			unsubscribe()
			return nil
		case <-signal:
		case <-poll.C:
		case <-heartbeat.C:
			if writer.heartbeat() != nil {
				unsubscribe()
				return nil
			}
		}
		unsubscribe()
	}
}
func (h *ModuleHTTP) upload(w http.ResponseWriter, r *http.Request) error {
	files := h.module.Attachments
	if r.URL.Path == "/api/v1/chat/uploads" {
		if e := only(r, "POST"); e != nil {
			return e
		}
		upload, e := web.RequestMultipart(w, r)
		if e != nil {
			return e
		}
		list := upload.Files["file"]
		if len(list) == 0 {
			return web.MissingParameter("file")
		}
		file := list[0]
		if file.Size > files.Maximum {
			return invalid("文件不能超过 " + describeLimit(files.Maximum))
		}
		reader, e := file.Open()
		if e != nil {
			return e
		}
		data, e := io.ReadAll(io.LimitReader(reader, files.Maximum+1))
		closeErr := reader.Close()
		if e = errors.Join(e, closeErr); e != nil {
			return e
		}
		a, e := files.Store(r.Context(), data, &file.Filename, file.ContentType)
		if e != nil {
			return e
		}
		return respond(w, r, 200, a)
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/api/v1/chat/uploads/"), "/")
	if len(parts) != 2 || parts[1] != "content" {
		return web.NotFound()
	}
	if e := only(r, "GET"); e != nil {
		return e
	}
	data, e := files.Read(r.Context(), parts[0])
	if e != nil {
		return e
	}
	name := r.URL.Query().Get("filename")
	if strip(name) == "" {
		name = parts[0]
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", "inline; filename*=UTF-8''"+strings.ReplaceAll(url.QueryEscape(name), "+", "%20"))
	w.Header().Set("Cache-Control", "max-age=604800, private")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(200)
	if r.Method == "HEAD" {
		return nil
	}
	_, e = w.Write(data)
	return e
}
func describeLimit(n int64) string {
	if n%(1<<20) == 0 {
		return strconv.FormatInt(n/(1<<20), 10) + "MB"
	}
	if n%1024 == 0 {
		return strconv.FormatInt(n/1024, 10) + "KB"
	}
	return strconv.FormatInt(n, 10) + " 字节"
}
