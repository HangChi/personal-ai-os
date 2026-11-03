package chat

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"

	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
)

// NewHTTP 只提供 T400 的存储接口；流、上下文、runs、附件上传由后续任务装配。
// 认证/CSRF/限流仍由应用外层负责，不在此创建另一套安全链。
func NewHTTP(service *Service, zone *time.Location, logger *slog.Logger) (http.Handler, error) {
	if service == nil || zone == nil || logger == nil {
		return nil, errors.New("对话HTTP依赖不完整")
	}
	h := &HTTP{service, zone}
	return web.HandleErrors(h.serve, logger), nil
}

type HTTP struct {
	service *Service
	zone    *time.Location
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
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) error {
	const base = "/api/v1/chat/sessions"
	path := r.URL.Path
	if path == base {
		switch r.Method {
		case http.MethodPost:
			title, err := optionalString(w, r, "title")
			if err != nil {
				return err
			}
			result, err := h.service.Create(r.Context(), title)
			if err != nil {
				return err
			}
			return respond(w, r, http.StatusCreated, result)
		case http.MethodGet, http.MethodHead:
			result, err := h.service.List(r.Context())
			if err != nil {
				return err
			}
			return respond(w, r, http.StatusOK, result)
		default:
			return web.MethodNotSupported(r.Method, []string{"GET", "POST"})
		}
	}
	if path == base+"/search" {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		page, err := intParameter(r, "page", false)
		if err != nil {
			return err
		}
		size, err := intParameter(r, "size", false)
		if err != nil {
			return err
		}
		q := SessionQuery{Keyword: r.URL.Query().Get("keyword"), Size: 20}
		if page != nil {
			q.Page = *page
		}
		if size != nil {
			q.Size = *size
		}
		result, err := h.service.Search(r.Context(), q)
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, result)
	}
	if !strings.HasPrefix(path, base+"/") {
		return web.NotFound()
	}
	parts := strings.Split(strings.TrimPrefix(path, base+"/"), "/")
	if len(parts) > 2 || parts[0] == "" {
		return web.NotFound()
	}
	id := parts[0]
	if len(parts) == 1 {
		switch r.Method {
		case http.MethodPatch:
			title, err := optionalString(w, r, "title")
			if err != nil {
				return err
			}
			result, err := h.service.Rename(r.Context(), id, title)
			if err != nil {
				return err
			}
			return respond(w, r, http.StatusOK, result)
		case http.MethodDelete:
			if err := h.service.Delete(r.Context(), id); err != nil {
				return err
			}
			return respond(w, r, http.StatusOK, nil)
		default:
			return web.MethodNotSupported(r.Method, []string{"PATCH", "DELETE"})
		}
	}
	switch parts[1] {
	case "model":
		if r.Method != http.MethodPut {
			return web.MethodNotSupported(r.Method, []string{"PUT"})
		}
		model, err := optionalString(w, r, "model")
		if err != nil {
			return err
		}
		result, err := h.service.SetModel(r.Context(), id, model)
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, result)
	case "messages":
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		limit, err := intParameter(r, "limit", false)
		if err != nil {
			return err
		}
		before, err := intParameter(r, "beforeSeq", false)
		if err != nil {
			return err
		}
		result, err := h.service.Messages(r.Context(), id, limit, before)
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, result)
	case "rewind":
		if r.Method != http.MethodPost {
			return web.MethodNotSupported(r.Method, []string{"POST"})
		}
		from, err := intParameter(r, "fromSequenceNo", true)
		if err != nil {
			return err
		}
		result, err := h.service.Rewind(r.Context(), id, *from)
		if err != nil {
			return err
		}
		return respond(w, r, http.StatusOK, map[string]int{"deleted": result})
	case "export":
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		// produces 在调用服务前匹配，失败不访问数据库。
		if !markdownAcceptable(r.Header.Get("Accept")) {
			return web.NotAcceptable()
		}
		result, err := h.service.ExportMarkdown(r.Context(), id, h.zone)
		if err != nil {
			return err
		}
		w.Header().Set("Content-Type", "text/markdown;charset=UTF-8")
		w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+result.EncodedFilename)
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusOK)
			return nil
		}
		_, err = io.WriteString(w, result.Markdown)
		return err
	default:
		return web.NotFound()
	}
}
func optionalString(w http.ResponseWriter, r *http.Request, name string) (*string, error) {
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 10<<20))
	if err != nil {
		return nil, web.InvalidBody(err)
	}
	trimmed := bytes.TrimSpace(data)
	if len(trimmed) == 0 {
		return nil, nil
	}
	isNull := bytes.Equal(trimmed, []byte("null"))
	if isNull {
		data = []byte("{}")
	}
	r.Body = io.NopCloser(bytes.NewReader(data))
	object, err := web.DecodeJSONObject(w, r, 10<<20)
	if err != nil {
		return nil, err
	}
	if isNull {
		return nil, nil
	}
	return web.JSONStringField(object, name)
}
func intParameter(r *http.Request, name string, required bool) (*int, error) {
	items, ok := r.URL.Query()[name]
	if !ok || len(items) == 0 {
		if required {
			return nil, web.MissingParameter(name)
		}
		return nil, nil
	}
	raw := items[0]
	if strip(raw) == "" {
		if required {
			return nil, web.MissingParameter(name)
		}
		return nil, nil
	}
	n, err := parseJavaInt(raw)
	if err != nil {
		return nil, web.InvalidParameter(name)
	}
	v := int(n)
	return &v, nil
}

// Spring NumberUtils 先删除 Java 空白，再接受有符号十进制或显式十六进制。
// Integer.parseInt 按 UTF-16 char 接受 BMP 十进制数字，不能直接用 strconv.Atoi 代替。
func parseJavaInt(raw string) (int64, error) {
	text := strings.Map(func(r rune) rune {
		if javaSpace(r) {
			return -1
		}
		for _, table := range unicode.Nd.R16 {
			if uint32(r) >= uint32(table.Lo) && uint32(r) <= uint32(table.Hi) && (uint32(r)-uint32(table.Lo))%uint32(table.Stride) == 0 {
				return '0' + rune((uint32(r)-uint32(table.Lo))/uint32(table.Stride)%10)
			}
		}
		if r >= 'Ａ' && r <= 'Ｆ' {
			return 'A' + r - 'Ａ'
		}
		if r >= 'ａ' && r <= 'ｆ' {
			return 'a' + r - 'ａ'
		}
		return r
	}, raw)
	sign := ""
	digits := text
	if len(digits) > 0 && (digits[0] == '+' || digits[0] == '-') {
		sign = digits[:1]
		digits = digits[1:]
	}
	base := 10
	if strings.HasPrefix(digits, "0x") || strings.HasPrefix(digits, "0X") {
		base = 16
		digits = digits[2:]
	} else if strings.HasPrefix(digits, "#") {
		base = 16
		digits = digits[1:]
	}
	// Integer.decode 拒绝出现在进制前缀之后的符号。
	if strings.HasPrefix(digits, "+") || strings.HasPrefix(digits, "-") {
		return 0, errors.New("数值符号位置无效")
	}
	return strconv.ParseInt(sign+digits, base, 32)
}
func markdownAcceptable(accept string) bool {
	if accept == "" {
		return true
	}
	for _, part := range strings.Split(accept, ",") {
		media := strings.TrimSpace(strings.SplitN(part, ";", 2)[0])
		if media == "*/*" || strings.EqualFold(media, "text/*") || strings.EqualFold(media, "text/markdown") {
			return true
		}
	}
	return false
}
func decodeAttachment(object map[string]json.RawMessage) (a Attachment, err error) {
	if a.ID, err = web.JSONStringField(object, "id"); err != nil {
		return
	}
	if a.Filename, err = web.JSONStringField(object, "filename"); err != nil {
		return
	}
	if a.ContentType, err = web.JSONStringField(object, "contentType"); err != nil {
		return
	}
	raw, ok := object["size"]
	if !ok || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return a, errors.New("附件大小缺失")
	}
	if len(raw) > 0 && raw[0] == '"' {
		var text string
		if err = json.Unmarshal(raw, &text); err != nil {
			return
		}
		text = strip(text)
		if text == "" {
			return a, errors.New("附件大小为空")
		}
		a.Size, err = strconv.ParseInt(text, 10, 64)
		return
	}
	rational, ok := new(big.Rat).SetString(string(raw))
	if !ok {
		return a, errors.New("附件大小格式错误")
	}
	n := new(big.Int).Quo(rational.Num(), rational.Denom())
	if !n.IsInt64() {
		return a, errors.New("附件大小超出范围")
	}
	a.Size = n.Int64()
	return
}
