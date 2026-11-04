package httpmiddleware

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"mime"
	"net/http"
	"strconv"
	"strings"

	"golang.org/x/text/encoding/ianaindex"
	"golang.org/x/text/transform"
)

// JSONAcceptable 对齐 MVC 未声明 produces 的 Jackson 响应协商。
// 原框架仍将 q=0 的兼容类型作为候选，不自行增加与基线不同的排除规则。
func JSONAcceptable(accept string) bool {
	_, ok := SelectJSONMediaType(accept, []string{"application/json", "application/*+json"})
	return ok
}

// DecodeJSONObject 保留 Jackson 的精确字段名、未知字段忽略、重复字段取最后值。
// 字段类型由调用方按 DTO 逐项校验；不把 encoding/json 的大小写宽松匹配当作 Java 契约。
func DecodeJSONObject(w http.ResponseWriter, r *http.Request, maxBytes int64) (map[string]json.RawMessage, error) {
	return decodeJSONObject(w, r, maxBytes, false)
}

// DecodeOptionalJSONObject accepts an absent or JSON null request body, as an optional Spring DTO.
func DecodeOptionalJSONObject(w http.ResponseWriter, r *http.Request, maxBytes int64) (map[string]json.RawMessage, error) {
	if r.ContentLength == 0 {
		return nil, nil
	}
	return decodeJSONObject(w, r, maxBytes, true)
}

func decodeJSONObject(w http.ResponseWriter, r *http.Request, maxBytes int64, optional bool) (map[string]json.RawMessage, error) {
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
		if optional && err == io.EOF {
			return nil, nil
		}
		return nil, InvalidBody(err)
	}
	if object == nil && !optional {
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

func JSONIntField(obj map[string]json.RawMessage, key string) (*int, error) {
	raw := bytes.TrimSpace(obj[key])
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return nil, nil
	}
	var n int64
	if raw[0] == '"' {
		var s string
		if json.Unmarshal(raw, &s) != nil {
			return nil, InvalidBody(nil)
		}
		s = strings.TrimFunc(s, javaValidationWhitespace)
		if s == "" || s == "null" {
			return nil, nil
		}
		var e error
		n, e = strconv.ParseInt(s, 10, 32)
		if e != nil {
			return nil, InvalidBody(e)
		}
	} else {
		r, ok := new(big.Rat).SetString(string(raw))
		if !ok {
			return nil, InvalidBody(nil)
		}
		i := new(big.Int).Quo(r.Num(), r.Denom())
		if !i.IsInt64() {
			return nil, InvalidBody(nil)
		}
		n = i.Int64()
		if n < -2147483648 || n > 2147483647 {
			return nil, InvalidBody(nil)
		}
	}
	v := int(n)
	return &v, nil
}
func JSONBoolField(obj map[string]json.RawMessage, key string) (*bool, error) {
	raw := bytes.TrimSpace(obj[key])
	if len(raw) == 0 || bytes.Equal(raw, []byte("null")) {
		return nil, nil
	}
	var v bool
	if raw[0] == '"' {
		var s string
		if json.Unmarshal(raw, &s) != nil {
			return nil, InvalidBody(nil)
		}
		s = strings.TrimFunc(s, javaValidationWhitespace)
		if s == "" || s == "null" {
			return nil, nil
		}
		switch s {
		case "true", "True", "TRUE":
			v = true
		case "false", "False", "FALSE":
		default:
			return nil, InvalidBody(nil)
		}
	} else if bytes.Equal(raw, []byte("true")) {
		v = true
	} else if bytes.Equal(raw, []byte("false")) {
	} else {
		n, e := strconv.ParseInt(string(raw), 10, 64)
		if e != nil {
			return nil, InvalidBody(e)
		}
		v = n != 0
	}
	return &v, nil
}
