package chat

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/platform/api"
)

// Store 只提供显式事务；写入、序号分配与摘要失效始终使用同一事务连接。
type Store interface {
	Transact(context.Context, bool, func(Tx) error) error
}
type Tx interface {
	Session(context.Context, string, bool) (Session, error)
	InsertSession(context.Context, Session) error
	SaveSession(context.Context, Session) error
	Sessions(context.Context, SessionQuery) ([]Session, int64, error)
	CountMessages(context.Context, string) (int64, error)
	MaxSequence(context.Context, string) (int, error)
	RangeMessages(context.Context, string, int, int) ([]Message, error)
	MessageIdentity(context.Context, string, int) (int64, error)
	Messages(context.Context, string, MessageQuery) ([]Message, error)
	InsertMessage(context.Context, string, NewMessage, int, time.Time) error
	AttachmentSnapshots(context.Context, string, *int) ([]Attachment, error)
	DeleteMessages(context.Context, string, *int) (int, error)
	DeleteSession(context.Context, string) error
	InsertCitations(context.Context, int64, []Citation, time.Time) error
}

// AttachmentCleaner 必须由附件模块提供；仅在事务成功且连接已归还后调用。
// 未装配时拒绝删除带附件的历史，避免把尚未迁移的文件生命周期静默跳过。
type AttachmentCleaner func(context.Context, []Attachment)
type Service struct {
	store   Store
	cleanup AttachmentCleaner
	now     func() time.Time
}

func NewService(store Store, cleanup AttachmentCleaner) (*Service, error) {
	if store == nil {
		return nil, errors.New("对话仓储不能为空")
	}
	return &Service{store: store, cleanup: cleanup, now: time.Now}, nil
}
func (s *Service) Create(ctx context.Context, title *string) (result SessionDTO, err error) {
	normalized, err := normalizeTitle(title, true)
	if err != nil {
		return result, err
	}
	var bytes [16]byte
	if _, err = rand.Read(bytes[:]); err != nil {
		return result, errors.New("无法生成会话标识")
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	id := fmt.Sprintf("%x-%x-%x-%x-%x", bytes[0:4], bytes[4:6], bytes[6:8], bytes[8:10], bytes[10:16])
	now := s.now().UTC()
	session := Session{ConversationID: id, Title: normalized, CreatedAt: now, UpdatedAt: now}
	err = s.store.Transact(ctx, false, func(tx Tx) error { return tx.InsertSession(ctx, session) })
	if err == nil {
		result = session.DTO()
	}
	return
}
func (s *Service) EnsureWritable(ctx context.Context, id string) error {
	v, e := s.Require(ctx, id)
	if e != nil {
		return e
	}
	if v.ReadOnlyReport {
		return api.NewBusinessError(api.BusinessConflict, "报告会话只读，请新建对话")
	}
	return nil
}
func (s *Service) Require(ctx context.Context, id string) (result Session, err error) {
	if err = ValidateConversationID(id); err != nil {
		return
	}
	err = s.store.Transact(ctx, true, func(tx Tx) error { var e error; result, e = tx.Session(ctx, id, false); return e })
	return
}
func (s *Service) Rename(ctx context.Context, id string, title *string) (result SessionDTO, err error) {
	if err = ValidateConversationID(id); err != nil {
		return
	}
	normalized, err := normalizeTitle(title, false)
	if err != nil {
		return result, err
	}
	err = s.store.Transact(ctx, false, func(tx Tx) error {
		r, e := tx.Session(ctx, id, true)
		if e != nil {
			return e
		}
		r.Title = normalized
		if e = tx.SaveSession(ctx, r); e != nil {
			return e
		}
		r.MessageCount, e = tx.CountMessages(ctx, id)
		result = r.DTO()
		return e
	})
	return
}
func (s *Service) SetModel(ctx context.Context, id string, model *string) (result SessionDTO, err error) {
	if err = ValidateConversationID(id); err != nil {
		return
	}
	err = s.store.Transact(ctx, false, func(tx Tx) error {
		r, e := tx.Session(ctx, id, true)
		if e != nil {
			return e
		}
		normalized := strip(value(model))
		if javaLength(normalized) > 120 {
			return invalid("模型名长度不能超过 120 个字符")
		}
		r.ChatModel = nil
		if normalized != "" {
			r.ChatModel = ptr(normalized)
		}
		if e = tx.SaveSession(ctx, r); e != nil {
			return e
		}
		r.MessageCount, e = tx.CountMessages(ctx, id)
		result = r.DTO()
		return e
	})
	return
}
func (s *Service) Search(ctx context.Context, q SessionQuery) (result api.Page[SessionDTO], err error) {
	if !q.Export {
		q = q.Normalize()
	} else if q.Page < 0 || q.Size <= 0 {
		return result, errors.New("对话导出分页参数无效")
	}
	err = s.store.Transact(ctx, true, func(tx Tx) error {
		rows, total, e := tx.Sessions(ctx, q)
		if e != nil {
			return e
		}
		items := make([]SessionDTO, 0, len(rows))
		for _, row := range rows {
			items = append(items, row.DTO())
		}
		result = api.PageOfResult(items, q.Page, q.Size, total)
		return nil
	})
	return
}
func (s *Service) List(ctx context.Context) ([]SessionDTO, error) {
	p, e := s.Search(ctx, SessionQuery{All: true})
	return p.Items, e
}
func (s *Service) ExportBatch(ctx context.Context, page, size int) ([]SessionDTO, error) {
	if page < 0 || size <= 0 {
		return nil, errors.New("对话导出分页参数无效")
	}
	p, e := s.Search(ctx, SessionQuery{Page: page, Size: size, Export: true})
	return p.Items, e
}
func readMessages(ctx context.Context, tx Tx, id string, limit, before *int) ([]Message, error) {
	if _, err := tx.Session(ctx, id, false); err != nil {
		return nil, err
	}
	if limit != nil {
		if *limit <= 0 || *limit > 200 {
			return nil, invalid("limit 必须在 1~200 之间")
		}
		if before != nil && *before <= 0 {
			return nil, invalid("beforeSeq 必须为正整数")
		}
	} else {
		before = nil
	}
	return tx.Messages(ctx, id, MessageQuery{Limit: limit, BeforeSeq: before})
}
func (s *Service) Messages(ctx context.Context, id string, limit, before *int) (result []Message, err error) {
	if err = ValidateConversationID(id); err != nil {
		return
	}
	err = s.store.Transact(ctx, true, func(tx Tx) error { var e error; result, e = readMessages(ctx, tx, id, limit, before); return e })
	return
}
func (s *Service) cleanupReady(files []Attachment) error {
	if len(files) > 0 && s.cleanup == nil {
		return errors.New("附件清理尚未装配，拒绝删除带附件的历史")
	}
	return nil
}
func (s *Service) clean(ctx context.Context, files []Attachment) {
	if len(files) > 0 {
		s.cleanup(ctx, files)
	}
}
func (s *Service) Delete(ctx context.Context, id string) error {
	if err := ValidateConversationID(id); err != nil {
		return err
	}
	var files []Attachment
	err := s.store.Transact(ctx, false, func(tx Tx) error {
		if _, e := tx.Session(ctx, id, true); e != nil {
			return e
		}
		var e error
		files, e = tx.AttachmentSnapshots(ctx, id, nil)
		if e != nil {
			return e
		}
		if e = s.cleanupReady(files); e != nil {
			return e
		}
		if _, e = tx.DeleteMessages(ctx, id, nil); e != nil {
			return e
		}
		return tx.DeleteSession(ctx, id)
	})
	if err == nil {
		s.clean(ctx, files)
	}
	return err
}
func (s *Service) Rewind(ctx context.Context, id string, from int) (count int, err error) {
	if from <= 0 {
		return 0, invalid("fromSequenceNo 必须为正整数")
	}
	if err = ValidateConversationID(id); err != nil {
		return
	}
	var files []Attachment
	err = s.store.Transact(ctx, false, func(tx Tx) error {
		r, e := tx.Session(ctx, id, true)
		if e != nil {
			return e
		}
		if r.ReadOnlyReport {
			return api.NewBusinessError(api.BusinessConflict, "报告会话只读，请新建对话")
		}
		files, e = tx.AttachmentSnapshots(ctx, id, &from)
		if e != nil {
			return e
		}
		if e = s.cleanupReady(files); e != nil {
			return e
		}
		if r.SummaryUptoSeq >= from {
			r.Summary = nil
			r.SummaryUptoSeq = 0
			if e = tx.SaveSession(ctx, r); e != nil {
				return e
			}
		}
		count, e = tx.DeleteMessages(ctx, id, &from)
		return e
	})
	if err == nil {
		s.clean(ctx, files)
	}
	return
}
func (s *Service) InitializeTitle(ctx context.Context, id string, content *string) error {
	if strip(value(content)) == "" {
		return nil
	}
	return s.store.Transact(ctx, false, func(tx Tx) error {
		r, e := tx.Session(ctx, id, true)
		var b *api.BusinessError
		if errors.As(e, &b) && b.Code() == api.ResourceNotFound {
			return nil
		}
		if e != nil {
			return e
		}
		if r.Title != DefaultTitle {
			return nil
		}
		n, e := tx.CountMessages(ctx, id)
		if e != nil || n > 0 {
			return e
		}
		title := asciiWhitespace.ReplaceAllString(strip(*content), " ")
		units := utf16.Encode([]rune(title))
		if len(units) > 30 {
			// JDBC 会把截断产生的孤立代理项写为问号；避免 Go 解码成 U+FFFD 后改变历史。
			if units[29] >= 0xd800 && units[29] <= 0xdbff {
				r.Title = string(utf16.Decode(units[:29])) + "?"
			} else {
				r.Title = string(utf16.Decode(units[:30]))
			}
		} else {
			r.Title = title
		}
		return tx.SaveSession(ctx, r)
	})
}

// Append 是持久化历史的唯一追加入口，不裁剪历史；模型窗口选择在 T401 实现。
func (s *Service) Append(ctx context.Context, id string, messages []NewMessage) error {
	if len(messages) == 0 {
		return nil
	}
	if err := ValidateConversationID(id); err != nil {
		return err
	}
	return s.store.Transact(ctx, false, func(tx Tx) error {
		r, e := tx.Session(ctx, id, true)
		if e != nil {
			return e
		}
		seq, e := tx.MaxSequence(ctx, id)
		if e != nil {
			return e
		}
		if int64(seq)+int64(len(messages)) > 2147483647 {
			return errors.New("会话消息顺序号已达上限")
		}
		now := s.now().UTC()
		for _, m := range messages {
			switch m.Role {
			case "USER", "ASSISTANT", "SYSTEM", "TOOL":
			default:
				m.Role = "ASSISTANT"
			}
			seq++
			if e = tx.InsertMessage(ctx, id, m, seq, now); e != nil {
				return e
			}
		}
		r.UpdatedAt = now
		return tx.SaveSession(ctx, r)
	})
}
func (s *Service) AttachCitations(ctx context.Context, id string, hits []Citation) error {
	if len(hits) == 0 {
		return nil
	}
	if err := ValidateConversationID(id); err != nil {
		return err
	}
	return s.store.Transact(ctx, false, func(tx Tx) error {
		if _, e := tx.Session(ctx, id, true); e != nil {
			return e
		}
		limit := 50
		rows, e := tx.Messages(ctx, id, MessageQuery{Limit: &limit})
		if e != nil {
			return e
		}
		for i := len(rows) - 1; i >= 0; i-- {
			if strings.EqualFold(rows[i].Role, "ASSISTANT") {
				return tx.InsertCitations(ctx, rows[i].ID, hits, s.now().UTC())
			}
		}
		return nil
	})
}

var errCitationSnapshot = errors.New("保存引用快照失败")

// Complete 将助手回答与引用快照一次提交，done 之前不会暴露半完成的回答。
func (s *Service) Complete(ctx context.Context, id, content string, hits []Citation) error {
	if err := ValidateConversationID(id); err != nil {
		return err
	}
	return s.store.Transact(ctx, false, func(tx Tx) error {
		session, err := tx.Session(ctx, id, true)
		if err != nil {
			return err
		}
		seq, err := tx.MaxSequence(ctx, id)
		if err != nil {
			return err
		}
		if seq == 2147483647 {
			return errors.New("会话消息顺序号已达上限")
		}
		now := s.now().UTC()
		if err = tx.InsertMessage(ctx, id, NewMessage{Role: "ASSISTANT", Content: content}, seq+1, now); err != nil {
			return err
		}
		if len(hits) > 0 {
			messageID, err := tx.MessageIdentity(ctx, id, seq+1)
			if err != nil {
				return err
			}
			if err = tx.InsertCitations(ctx, messageID, hits, now); err != nil {
				return fmt.Errorf("%w: %w", errCitationSnapshot, err)
			}
		}
		session.UpdatedAt = now
		return tx.SaveSession(ctx, session)
	})
}
