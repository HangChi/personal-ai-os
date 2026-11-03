package httpmiddleware

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"golang.org/x/text/encoding/ianaindex"
	"golang.org/x/text/transform"
)

// JSONAcceptable 对齐 MVC 未声明 produces 的 Jackson 响应协商。
// 原框架仍将 q=0 的兼容类型作为候选，不自行增加与基线不同的排除规则。
func JSONAcceptable(accept string) bool {
	if accept == "" {
		return true
	}
	for _, part := range strings.Split(accept, ",") {
		media, _, err := mime.ParseMediaType(strings.TrimSpace(part))
		if err == nil && (media == "*/*" || media == "application/*" || media == "application/json" || media == "application/*+json" || strings.HasPrefix(media, "application/") && strings.HasSuffix(media, "+json")) {
			return true
		}
	}
	return false
}

// DecodeJSONObject 保留 Jackson 的精确字段名、未知字段忽略、重复字段取最后值。
// 字段类型由调用方按 DTO 逐项校验；不把 encoding/json 的大小写宽松匹配当作 Java 契约。
func DecodeJSONObject(w http.ResponseWriter, r *http.Request, maxBytes int64) (map[string]json.RawMessage, error) {
	media, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "application/json" && !(strings.HasPrefix(media, "application/") && strings.HasSuffix(media, "+json")) {
		// 原全局处理器未特判 HttpMediaTypeNotSupportedException，实际为通用500。
		return nil, errors.New("请求媒体类型不受支持")
	}
	var reader io.Reader = http.MaxBytesReader(w, r.Body, maxBytes)
	if charset := params["charset"]; charset != "" && !strings.EqualFold(charset, "UTF-8") {
		encoding, err := ianaindex.MIME.Encoding(charset)
		if err != nil || encoding == nil {
			return nil, errors.New("请求字符集不受支持")
		}
		reader = transform.NewReader(reader, encoding.NewDecoder())
	}
	data, err := io.ReadAll(reader)
	if err != nil {
		return nil, InvalidBody(err)
	}
	data = bytes.TrimPrefix(data, []byte{0xef, 0xbb, 0xbf})
	decoder := json.NewDecoder(bytes.NewReader(data))
	var object map[string]json.RawMessage
	if err := decoder.Decode(&object); err != nil {
		return nil, InvalidBody(err)
	}
	if object == nil {
		return nil, InvalidBody(nil)
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		return nil, InvalidBody(err)
	}
	return object, nil
}

// JSONStringField 对齐 Jackson String 的 scalar coercion；null/省略仍为 nil。
func JSONStringField(object map[string]json.RawMessage, name string) (*string, error) {
	raw, found := object[name]
	if !found || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
		return nil, nil
	}
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.UseNumber()
	var value any
	if err := decoder.Decode(&value); err != nil {
		return nil, InvalidBody(err)
	}
	var text string
	switch value := value.(type) {
	case string:
		text = value
	case json.Number:
		text = value.String()
	case bool:
		if value {
			text = "true"
		} else {
			text = "false"
		}
	default:
		return nil, InvalidBody(nil)
	}
	return &text, nil
}
