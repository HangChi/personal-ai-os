package knowledge

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

const basePath = "/api/v1/knowledge"

func OwnsPath(path string) bool { return path == basePath || strings.HasPrefix(path, basePath+"/") }

type HTTP struct {
	Store      *Store
	Jobs       *Jobs
	Governance *Governance
	Search     KnowledgeSearchPort
	QA         *QA
	Projection *Projection
}

func (h *HTTP) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		status, value, e := h.serve(w, r)
		if e != nil {
			return e
		}
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Type", media)
			w.WriteHeader(status)
			return nil
		}
		return api.WriteControllerMediaJSON(w, status, api.OK(value), media)
	}, logger)
}
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) (int, any, error) {
	if r.URL.Path == basePath+"/captures" || strings.HasPrefix(r.URL.Path, basePath+"/captures/") {
		return h.serveCaptures(w, r)
	}
	ctx := r.Context()
	path := strings.TrimPrefix(r.URL.Path, basePath)
	method := r.Method
	require := func(allowed ...string) error {
		for _, m := range allowed {
			if method == m || method == http.MethodHead && m == http.MethodGet {
				return nil
			}
		}
		return web.MethodNotSupported(method, allowed)
	}
	response := func(v any, e error) (int, any, error) { return 200, v, e }
	switch path {
	case "/documents":
		if method == http.MethodGet || method == http.MethodHead {
			v, e := h.Store.List(ctx)
			return response(v, e)
		}
		if e := require("GET", "POST"); e != nil {
			return response(nil, e)
		}
		filename, title, _, payload, e := readUpload(w, r)
		if e != nil {
			return response(nil, e)
		}
		v, e := h.Store.Ingest(ctx, filename, stringValue(title), payload)
		return 201, v, e
	case "/documents/search":
		if e := require("GET"); e != nil {
			return response(nil, e)
		}
		page, e := queryInt(r, "page", 0)
		if e != nil {
			return response(nil, e)
		}
		size, e := queryInt(r, "size", 20)
		if e != nil {
			return response(nil, e)
		}
		v, e := h.Store.SearchDocuments(ctx, r.URL.Query().Get("keyword"), page, size)
		return response(v, e)
	case "/jobs":
		if method == http.MethodGet || method == http.MethodHead {
			v, e := h.Jobs.Recent(ctx)
			return response(v, e)
		}
		if e := require("GET", "POST"); e != nil {
			return response(nil, e)
		}
		filename, title, target, payload, e := readUpload(w, r)
		if e != nil {
			return response(nil, e)
		}
		id := ""
		if raw := r.Header.Get("Idempotency-Key"); raw != "" {
			var ok bool
			id, ok = api.ParseJavaUUID(raw)
			if !ok {
				return response(nil, web.InvalidParameter("Idempotency-Key"))
			}
		}
		v, e := h.Jobs.Submit(ctx, id, target, filename, title, payload)
		return 202, v, e
	case "/search", "/ask":
		if e := require("POST"); e != nil {
			return response(nil, e)
		}
		obj, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return response(nil, e)
		}
		key, label := "query", "检索词"
		if path == "/ask" {
			key, label = "question", "问题"
		}
		text, e := web.JSONStringField(obj, key)
		if e != nil {
			return response(nil, e)
		}
		if text == nil || strip(*text) == "" {
			return response(nil, invalid(label+"不能为空"))
		}
		if chars(*text) > 500 {
			return response(nil, invalid(label+"长度不能超过 500"))
		}
		limit, e := web.JSONIntField(obj, "topK")
		if e != nil {
			return response(nil, e)
		}
		if path == "/ask" {
			v, e := h.QA.Ask(ctx, *text, limit)
			return response(v, e)
		}
		v, e := h.Search.Retrieve(ctx, *text, limit)
		return response(map[string]any{"query": *text, "hits": v.Hits, "retrieval": v}, e)
	case "/governance/spaces":
		if method == http.MethodGet || method == http.MethodHead {
			v, e := h.Governance.Spaces(ctx)
			return response(v, e)
		}
		if e := require("GET", "POST"); e != nil {
			return response(nil, e)
		}
		obj, e := web.DecodeJSONObject(w, r, 10<<20)
		if e != nil {
			return response(nil, e)
		}
		name, e := web.JSONStringField(obj, "name")
		if e != nil {
			return response(nil, e)
		}
		v, e := h.Governance.CreateSpace(ctx, stringValue(name))
		return response(v, e)
	case "/governance/sources":
		if e := require("GET"); e != nil {
			return response(nil, e)
		}
		v, e := h.Governance.Sources(ctx)
		return response(v, e)
	case "/governance/conflicts":
		if e := require("GET"); e != nil {
			return response(nil, e)
		}
		v, e := h.Governance.Conflicts(ctx)
		return response(v, e)
	case "/search-index", "/search-index/reindex":
		if h.Projection == nil {
			return response(nil, web.NotFound())
		}
		if path == "/search-index" {
			if e := require("GET"); e != nil {
				return response(nil, e)
			}
			n, e := h.Projection.Pending(ctx)
			return response(map[string]int64{"pending": n}, e)
		}
		if e := require("POST"); e != nil {
			return response(nil, e)
		}
		n, e := h.Projection.Reindex(ctx)
		return response(map[string]int64{"queued": n}, e)
	}
	for _, prefix := range []string{"/documents/", "/jobs/", "/governance/sources/", "/governance/conflicts/"} {
		if !strings.HasPrefix(path, prefix) {
			continue
		}
		parts := strings.Split(strings.TrimPrefix(path, prefix), "/")
		if len(parts) > 2 || parts[0] == "" {
			return response(nil, web.NotFound())
		}
		id, ok := api.ParseJavaUUID(parts[0])
		if !ok {
			return response(nil, web.InvalidParameter("id"))
		}
		action := ""
		if len(parts) == 2 {
			action = parts[1]
		}
		switch prefix {
		case "/documents/":
			if action != "" {
				return response(nil, web.NotFound())
			}
			if e := require("PUT", "DELETE"); e != nil {
				return response(nil, e)
			}
			if method == "DELETE" {
				n, e := h.Store.Delete(ctx, id)
				return response(map[string]any{"deleted": true, "deletedChunks": n}, e)
			}
			filename, _, _, payload, e := readUpload(w, r)
			if e != nil {
				return response(nil, e)
			}
			v, e := h.Store.Update(ctx, id, filename, payload)
			return response(v, e)
		case "/jobs/":
			if action != "" {
				return response(nil, web.NotFound())
			}
			if e := require("GET"); e != nil {
				return response(nil, e)
			}
			v, e := h.Jobs.Get(ctx, id)
			return response(v, e)
		case "/governance/sources/":
			if action == "review" {
				if e := require("POST"); e != nil {
					return response(nil, e)
				}
				return response(map[string]bool{"queued": true}, h.Governance.Retry(ctx, id))
			}
			if action != "" {
				return response(nil, web.NotFound())
			}
			if e := require("PUT"); e != nil {
				return response(nil, e)
			}
			obj, e := web.DecodeJSONObject(w, r, 10<<20)
			if e != nil {
				return response(nil, e)
			}
			m, e := readMetadata(obj)
			if e != nil {
				return response(nil, e)
			}
			v, e := h.Governance.Update(ctx, id, m)
			return response(v, e)
		case "/governance/conflicts/":
			if action != "resolve" {
				return response(nil, web.NotFound())
			}
			if e := require("POST"); e != nil {
				return response(nil, e)
			}
			obj, e := web.DecodeJSONObject(w, r, 10<<20)
			if e != nil {
				return response(nil, e)
			}
			decision, e := web.JSONStringField(obj, "decision")
			if e != nil {
				return response(nil, e)
			}
			winner, e := jsonUUID(obj, "winnerId")
			if e != nil {
				return response(nil, e)
			}
			reason, e := web.JSONStringField(obj, "reason")
			if e != nil {
				return response(nil, e)
			}
			return response(map[string]bool{"resolved": true}, h.Governance.Resolve(ctx, id, Resolution{stringValue(decision), winner, stringValue(reason)}))
		}
	}
	return response(nil, web.NotFound())
}
func stringValue(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
func queryInt(r *http.Request, key string, fallback int) (int, error) {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return fallback, nil
	}
	n, e := strconv.ParseInt(strings.TrimSpace(raw), 10, 32)
	if e != nil {
		return 0, web.InvalidParameter(key)
	}
	return int(n), nil
}
func readUpload(w http.ResponseWriter, r *http.Request) (string, *string, string, []byte, error) {
	upload, e := web.RequestMultipart(w, r)
	if e != nil {
		return "", nil, "", nil, e
	}
	files := upload.Files["file"]
	if len(files) == 0 {
		return "", nil, "", nil, web.MissingParameter("file")
	}
	f := files[0]
	reader, e := f.Open()
	if e != nil {
		return "", nil, "", nil, e
	}
	defer reader.Close()
	b, e := io.ReadAll(reader)
	if e != nil {
		return "", nil, "", nil, e
	}
	if len(b) == 0 {
		return "", nil, "", nil, unsupported("请选择要上传的文件")
	}
	var title *string
	// The baseline multipart wrapper exposes form values before URL parameters;
	// Spring converts repeated String parameters to a comma-delimited value.
	parameters := func(name string) []string {
		values := append([]string(nil), upload.Values[name]...)
		return append(values, r.URL.Query()[name]...)
	}
	if values := parameters("title"); len(values) > 0 {
		value := strings.Join(values, ",")
		title = &value
	}
	target := ""
	if values := parameters("documentId"); len(values) > 0 && strip(values[0]) != "" {
		var ok bool
		// StringToUUIDConverter checks hasText, then uses String.trim (ASCII).
		raw := strings.TrimFunc(values[0], func(r rune) bool { return r <= 0x20 })
		target, ok = api.ParseJavaUUID(raw)
		if !ok {
			return "", nil, "", nil, web.InvalidParameter("documentId")
		}
	}
	return f.Filename, title, target, b, nil
}
func jsonUUID(obj map[string]json.RawMessage, key string) (*string, error) {
	s, e := web.JSONStringField(obj, key)
	if e != nil || s == nil {
		return s, e
	}
	id, ok := api.ParseJavaUUID(*s)
	if !ok {
		return nil, web.InvalidBody(nil)
	}
	return &id, nil
}
func readMetadata(obj map[string]json.RawMessage) (Metadata, error) {
	var m Metadata
	id, e := jsonUUID(obj, "spaceId")
	if e != nil {
		return m, e
	}
	m.SpaceID = stringValue(id)
	for _, f := range []struct {
		key    string
		target *string
	}{{"version", &m.Version}, {"scope", &m.Scope}, {"authority", &m.Authority}, {"lifecycle", &m.Lifecycle}} {
		v, e := web.JSONStringField(obj, f.key)
		if e != nil {
			return m, e
		}
		*f.target = stringValue(v)
	}
	m.EffectiveFrom, e = web.JSONStringField(obj, "effectiveFrom")
	if e == nil {
		m.EffectiveUntil, e = web.JSONStringField(obj, "effectiveUntil")
	}
	return m, e
}

// Vision uses the plain model with one image, no tools or conversation history.
func Vision(runtime interface {
	Target(context.Context, string) (*model.ChatClient, error)
}) VisionExtractor {
	return func(ctx context.Context, data []byte, mime string) (string, error) {
		client, e := runtime.Target(ctx, "")
		if e != nil {
			return "", unsupported("当前未配置 AI 模型，无法解析图片（请先配置 ollama 或 openai）")
		}
		result, e := client.Call(ctx, model.ChatRequest{Messages: []model.Message{{Role: "user", Content: "请把这张图片里的文字逐字转录出来，尽量保留原有的分段与列表结构。只输出转录结果，不要解释，不要补充图片里没有的内容。如果图片里没有任何可读文字，只回复两个字：无文本", Images: []model.Image{{MIME: mime, Data: data}}}}})
		if e != nil {
			return "", api.NewBusinessError(api.AIUpstreamError, "无法连接视觉模型服务")
		}
		return result.Content, nil
	}
}
