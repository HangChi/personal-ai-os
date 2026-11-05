package chat

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
	"unicode/utf16"
)

type ExportDocument struct {
	Filename        string `json:"filename"`
	Markdown        string `json:"markdown"`
	EncodedFilename string `json:"-"`
}

func (s *Service) ExportMarkdown(ctx context.Context, id string, zone *time.Location) (result ExportDocument, err error) {
	if err = ValidateConversationID(id); err != nil {
		return
	}
	if zone == nil {
		return result, errors.New("Markdown导出必须提供显式时区")
	}
	err = s.store.Transact(ctx, true, func(tx Tx) error {
		session, e := tx.Session(ctx, id, false)
		if e != nil {
			return e
		}
		messages, e := tx.Messages(ctx, id, MessageQuery{})
		if e != nil {
			return e
		}
		result = RenderMarkdown(session, messages, zone)
		return nil
	})
	return
}
func RenderMarkdown(session Session, messages []Message, zone *time.Location) ExportDocument {
	title := session.Title
	if strip(title) == "" {
		title = "未命名会话"
	}
	var out strings.Builder
	fmt.Fprintf(&out, "# %s\n\n> 创建于 %s · 共 %d 条消息\n> 导出自 Personal AI OS\n\n---\n\n", title, session.CreatedAt.In(zone).Format("2006-01-02 15:04"), len(messages))
	for _, m := range messages {
		role := "助手"
		if strings.EqualFold(m.Role, "USER") {
			role = "你"
		}
		content := strip(m.Content)
		if strings.Count(content, "```")%2 == 1 {
			content += "\n```"
		}
		fmt.Fprintf(&out, "### %s\n\n%s\n\n", role, content)
		if len(m.Attachments) > 0 {
			names := make([]string, len(m.Attachments))
			for i, a := range m.Attachments {
				names[i] = value(a.Filename)
				if a.Filename == nil {
					names[i] = "null"
				}
			}
			fmt.Fprintf(&out, "**附件**：%s\n\n", strings.Join(names, "、"))
		}
		if len(m.Citations) > 0 {
			out.WriteString("**引用来源**\n\n")
			for _, c := range m.Citations {
				label := value(c.DocumentTitle)
				if strip(label) == "" {
					label = "未命名"
				}
				if c.URL != "" {
					fmt.Fprintf(&out, "%d. 《%s》 <%s>\n", c.Index, label, c.URL)
					continue
				}
				filename := value(c.Filename)
				if strip(filename) == "" {
					filename = "未命名"
				}
				fmt.Fprintf(&out, "%d. 《%s》（%s，第 %d 段）\n", c.Index, label, filename, c.ChunkIndex+1)
			}
			out.WriteByte('\n')
		}
		out.WriteString("---\n\n")
	}
	if len(messages) == 0 {
		out.WriteString("_（这个会话还没有消息）_\n")
	}
	safe := strings.Map(func(r rune) rune {
		if strings.ContainsRune("\\/:*?\"<>|\r\n", r) {
			return '_'
		}
		return r
	}, title)
	safe = strip(safe)
	units := utf16.Encode([]rune(safe))
	headerSafe := safe
	if len(units) > 60 {
		safe = string(utf16.Decode(units[:60]))
		headerSafe = safe
		if units[59] >= 0xd800 && units[59] <= 0xdbff {
			headerSafe = string(utf16.Decode(units[:59])) + "?"
			safe = headerSafe
		}
	}
	suffix := "-" + session.CreatedAt.In(zone).Format("20060102-1504") + ".md"
	// URLEncoder 保留 * 而编码 ~，与 url.QueryEscape 的默认规则不同。
	encoded := url.QueryEscape(headerSafe + suffix)
	encoded = strings.ReplaceAll(encoded, "+", "%20")
	encoded = strings.ReplaceAll(encoded, "%2A", "*")
	encoded = strings.ReplaceAll(encoded, "~", "%7E")
	return ExportDocument{Filename: safe + suffix, Markdown: out.String(), EncodedFilename: encoded}
}
