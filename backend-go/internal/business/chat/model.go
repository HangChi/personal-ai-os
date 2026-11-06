package chat

import (
	"encoding/json"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"

	"golang.org/x/text/cases"
	"golang.org/x/text/language"
	"personal-ai-os/backend-go/internal/platform/api"
)

const DefaultTitle = "新会话"

var conversationID = regexp.MustCompile(`^[A-Za-z0-9-]{1,64}$`)
var asciiWhitespace = regexp.MustCompile(`[\t\n\v\f\r ]+`)

// Session 的摘要是内部状态；对外只返回 SessionDTO。
type Session struct {
	ReadOnlyReport        bool
	ConversationID, Title string
	CreatedAt, UpdatedAt  time.Time
	ChatModel, Summary    *string
	SummaryUptoSeq        int
	MessageCount          int64
}
type SessionDTO struct {
	ReadOnlyReport bool      `json:"readOnlyReport,omitempty"`
	ConversationID string    `json:"conversationId"`
	Title          string    `json:"title"`
	CreatedAt      time.Time `json:"createdAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
	MessageCount   int64     `json:"messageCount"`
	ChatModel      *string   `json:"chatModel"`
}

func (s Session) DTO() SessionDTO {
	return SessionDTO{s.ReadOnlyReport, s.ConversationID, s.Title, s.CreatedAt, s.UpdatedAt, s.MessageCount, s.ChatModel}
}

type Attachment struct {
	null        bool
	ID          *string `json:"id"`
	Filename    *string `json:"filename"`
	ContentType *string `json:"contentType"`
	Size        int64   `json:"size"`
}
type Citation struct {
	Index         int     `json:"index"`
	DocumentID    string  `json:"documentId"`
	DocumentTitle *string `json:"documentTitle"`
	Filename      *string `json:"filename"`
	ChunkIndex    int     `json:"chunkIndex"`
	Content       string  `json:"content"`
	URL           string  `json:"url,omitempty"`
}
type Message struct {
	ID          int64        `json:"id"`
	Role        string       `json:"role"`
	Content     string       `json:"content"`
	SequenceNo  int          `json:"sequenceNo"`
	CreatedAt   time.Time    `json:"createdAt"`
	Citations   []Citation   `json:"citations"`
	Attachments []Attachment `json:"attachments"`
}
type NewMessage struct {
	Role, Content string
	Attachments   []Attachment
}
type SessionQuery struct {
	Keyword     string
	Page, Size  int
	All, Export bool
}

func (q SessionQuery) Normalize() SessionQuery {
	if q.Page < 0 {
		q.Page = 0
	}
	if q.Size <= 0 {
		q.Size = 20
	}
	if q.Size > 100 {
		q.Size = 100
	}
	return q
}

type MessageQuery struct {
	Limit, BeforeSeq *int
}

func ValidateConversationID(id string) error {
	if !conversationID.MatchString(id) {
		return invalid("会话标识格式非法")
	}
	return nil
}
func invalid(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }
func notFound(id string) error {
	return api.NewBusinessError(api.ResourceNotFound, "会话不存在："+id)
}
func ptr(s string) *string { return &s }
func value(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
func javaSpace(r rune) bool {
	return r == '\t' || r == '\n' || r == '\v' || r == '\f' || r == '\r' || r >= 0x1c && r <= 0x1f || r != 0xa0 && r != 0x2007 && r != 0x202f && (unicode.Is(unicode.Zs, r) || r == 0x2028 || r == 0x2029)
}
func strip(s string) string   { return strings.TrimFunc(s, javaSpace) }
func javaLength(s string) int { return len(utf16.Encode([]rune(s))) }
func normalizeTitle(title *string, allowDefault bool) (string, error) {
	s := strip(value(title))
	if s == "" {
		if allowDefault {
			return DefaultTitle, nil
		}
		return "", invalid("标题不能为空")
	}
	if javaLength(s) > 200 {
		return "", invalid("标题长度不能超过 200")
	}
	return s, nil
}
func likePattern(keyword string) string {
	s := cases.Lower(language.Und).String(strip(keyword))
	return "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s) + "%"
}

// snapshots 对齐原 List.of(array)：任意 null 元素或坏 JSON 使整份快照退为空数组。
// String 的标量转换复用 HTTP 层；读取旧库时同样不允许大小写宽松匹配字段。
func snapshots(raw *string) []Attachment {
	result := []Attachment{}
	if raw == nil || strip(*raw) == "" {
		return result
	}
	var rows []map[string]json.RawMessage
	if json.Unmarshal([]byte(*raw), &rows) != nil {
		return result
	}
	for _, row := range rows {
		if row == nil {
			return []Attachment{}
		}
		a, err := decodeAttachment(row)
		if err != nil {
			return []Attachment{}
		}
		result = append(result, a)
	}
	return result
}

// 附件名片中的 null 必须原样参与运行请求的幂等比较；验证阶段会忽略它。
func (a Attachment) MarshalJSON() ([]byte, error) {
	if a.null {
		return []byte("null"), nil
	}
	type plain Attachment
	return json.Marshal(plain(a))
}
func (a *Attachment) UnmarshalJSON(raw []byte) error {
	if strings.TrimSpace(string(raw)) == "null" {
		*a = Attachment{null: true}
		return nil
	}
	type plain Attachment
	var v plain
	if e := json.Unmarshal(raw, &v); e != nil {
		return e
	}
	*a = Attachment(v)
	return nil
}
