// Package model 迁移模型配置的存储、解析与协议边界。
package model

import (
	"errors"
	"maps"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/config"
)

type Source string

const (
	Database    Source = "DATABASE"
	Environment Source = "ENVIRONMENT"
	Default     Source = "DEFAULT"
)

// Temperature 保留 Jackson 对 Double 非有限值的字符串编码。
// 这里只解析既有配置，不用新的范围校验改变 Java 的读取语义。
type Temperature float64

func (t Temperature) MarshalJSON() ([]byte, error) {
	switch {
	case math.IsNaN(float64(t)):
		return []byte(`"NaN"`), nil
	case math.IsInf(float64(t), 1):
		return []byte(`"Infinity"`), nil
	case math.IsInf(float64(t), -1):
		return []byte(`"-Infinity"`), nil
	default:
		return []byte(strconv.FormatFloat(float64(t), 'g', -1, 64)), nil
	}
}

// Values 是数据库的意图值。nil 表示逐字段回落；只能显式映射成安全 DTO。
type Values struct {
	BaseURL, APIKey, ChatModel, EmbeddingModel, AudioModel *string
	Temperature                                            *Temperature
	Revision                                               *int
	UpdatedAt                                              *time.Time
}

func (Values) String() string               { return "ModelValues{redacted}" }
func (v Values) GoString() string           { return v.String() }
func (Values) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

// Resolved 只供服务层/协议适配器使用，凭据不参与通用序列化或格式化。
type Resolved struct {
	BaseURL, ChatModel, EmbeddingModel string
	AudioModel                         *string
	Temperature                        *Temperature
	Revision                           *int
	UpdatedAt                          *time.Time
	Sources                            map[string]Source
	apiKey                             string
}

func (Resolved) String() string               { return "ResolvedModel{redacted}" }
func (r Resolved) GoString() string           { return r.String() }
func (Resolved) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func (r Resolved) APIKey() string             { return r.apiKey }
func (r Resolved) HasAPIKey() bool            { return strip(r.apiKey) != "" }
func (r Resolved) HasAudioModel() bool        { return r.AudioModel != nil && strip(*r.AudioModel) != "" }

// Resolver 只使用已显式加载的属性，不读取进程环境、.env 或远程服务。
// resolved env 值与原始 OPENAI_* 变量分别保留，以复现来源徽章。
type Resolver struct {
	env     Values
	defined map[string]bool
}

func (Resolver) String() string               { return "ModelResolver{redacted}" }
func (r Resolver) GoString() string           { return r.String() }
func (Resolver) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

func NewResolver(properties *config.Properties) (*Resolver, error) {
	if properties == nil {
		return nil, errors.New("模型配置属性不能为空")
	}
	r := &Resolver{defined: make(map[string]bool)}
	for _, field := range []struct {
		property, variable, name string
		target                   **string
	}{
		{"spring.ai.openai.base-url", "OPENAI_BASE_URL", "baseUrl", &r.env.BaseURL},
		{"spring.ai.openai.api-key", "OPENAI_API_KEY", "apiKey", &r.env.APIKey},
		{"spring.ai.openai.chat.model", "OPENAI_CHAT_MODEL", "chatModel", &r.env.ChatModel},
		{"spring.ai.openai.embedding.model", "OPENAI_EMBEDDING_MODEL", "embeddingModel", &r.env.EmbeddingModel},
	} {
		value, _, err := properties.Get(field.property)
		if err != nil {
			return nil, err
		}
		*field.target = blankToNull(&value)
		raw, found, err := properties.Get(field.variable)
		if err != nil {
			return nil, err
		}
		// 原 isEnvDefined 实现要求非空；即便其注释宣称空值也算，仍以实现为准。
		r.defined[field.name] = found && strip(raw) != ""
	}
	raw, _, err := properties.Get("spring.ai.openai.chat.temperature")
	if err != nil {
		return nil, err
	}
	r.env.Temperature = parseTemperature(raw)
	return r, nil
}

func (r *Resolver) Resolve(values Values) Resolved {
	out := Resolved{Sources: make(map[string]Source, 6), Revision: copyPointer(values.Revision), UpdatedAt: copyPointer(values.UpdatedAt)}
	for _, field := range []struct {
		name     string
		db, env  *string
		fallback string
		target   *string
	}{
		{"baseUrl", values.BaseURL, r.env.BaseURL, "https://api.openai.com", &out.BaseURL},
		{"apiKey", values.APIKey, r.env.APIKey, "", &out.apiKey},
		{"chatModel", values.ChatModel, r.env.ChatModel, "gpt-4o-mini", &out.ChatModel},
		{"embeddingModel", values.EmbeddingModel, r.env.EmbeddingModel, "text-embedding-3-small", &out.EmbeddingModel},
	} {
		*field.target = firstNonBlank(field.db, field.env, field.fallback)
		source := Default
		if field.db != nil && strip(*field.db) != "" {
			source = Database
		} else if r.defined[field.name] {
			source = Environment
		}
		out.Sources[field.name] = source
	}
	out.AudioModel = blankToNull(values.AudioModel)
	out.Sources["audioModel"] = Default
	if out.AudioModel != nil {
		out.Sources["audioModel"] = Database
	}
	out.Temperature = copyPointer(values.Temperature)
	out.Sources["temperature"] = Database
	if out.Temperature == nil {
		out.Temperature = copyPointer(r.env.Temperature)
		out.Sources["temperature"] = Default
		if out.Temperature != nil {
			out.Sources["temperature"] = Environment
		}
	}
	return out
}

func (r Resolved) DTO() ConfigDTO {
	return ConfigDTO{BaseURL: r.BaseURL, APIKeyMasked: Mask(r.apiKey), HasAPIKey: r.HasAPIKey(), ChatModel: r.ChatModel,
		Temperature: copyPointer(r.Temperature), EmbeddingModel: r.EmbeddingModel, Revision: copyPointer(r.Revision), UpdatedAt: copyPointer(r.UpdatedAt),
		Sources: maps.Clone(r.Sources), HotReloadable: true, EmbeddingHotReloadable: false}
}

func firstNonBlank(db, env *string, fallback string) string {
	for _, value := range []*string{db, env} {
		if value != nil && strip(*value) != "" {
			return *value
		}
	}
	return fallback
}

func copyPointer[T any](value *T) *T {
	if value == nil {
		return nil
	}
	out := *value
	return &out
}
func blankToNull(value *string) *string {
	if value == nil {
		return nil
	}
	s := strip(*value)
	if s == "" {
		return nil
	}
	return &s
}

func javaWhitespace(r rune) bool {
	return r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || r >= 0x1c && r <= 0x1f || r != 0xa0 && r != 0x2007 && r != 0x202f && (unicode.Is(unicode.Zs, r) || r == 0x2028 || r == 0x2029)
}
func strip(value string) string { return strings.TrimFunc(value, javaWhitespace) }

// 按 Java UTF-16 单元取末四位，不按 UTF-8 字节截断密钥。
func Mask(key string) string {
	if strip(key) == "" {
		return ""
	}
	units := utf16.Encode([]rune(key))
	if len(units) <= 4 {
		return "****"
	}
	tail := units[len(units)-4:]
	runes := make([]rune, 0, 4)
	for i := 0; i < len(tail); i++ {
		unit := tail[i]
		if unit >= 0xd800 && unit <= 0xdbff && i+1 < len(tail) && tail[i+1] >= 0xdc00 && tail[i+1] <= 0xdfff {
			runes = append(runes, utf16.DecodeRune(rune(unit), rune(tail[i+1])))
			i++
		} else if utf16.IsSurrogate(rune(unit)) {
			// 原 substring 可能切开代理对；原 Java UTF-8 输出会替换为问号。
			runes = append(runes, '?')
		} else {
			runes = append(runes, rune(unit))
		}
	}
	return "****" + string(runes)
}

var javaDouble = regexp.MustCompile(`^[+-]?(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?|0[xX](?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP][+-]?[0-9]+)[fFdD]?$`)

func parseTemperature(raw string) *Temperature {
	value := strings.TrimFunc(strip(raw), func(r rune) bool { return r <= 0x20 })
	switch value {
	case "NaN", "+NaN", "-NaN":
		n := Temperature(math.NaN())
		return &n
	case "Infinity", "+Infinity":
		n := Temperature(math.Inf(1))
		return &n
	case "-Infinity":
		n := Temperature(math.Inf(-1))
		return &n
	}
	if !javaDouble.MatchString(value) {
		return nil
	}
	value = strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(value, "f"), "F"), "d"), "D")
	n, err := strconv.ParseFloat(value, 64)
	if err != nil && !math.IsInf(n, 0) {
		return nil
	}
	result := Temperature(n)
	return &result
}
