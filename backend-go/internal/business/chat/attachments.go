package chat

import (
	"archive/zip"
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"encoding/xml"
	"errors"
	"io"
	"mime"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"personal-ai-os/backend-go/internal/business/agent"
	chatcontext "personal-ai-os/backend-go/internal/business/context"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
)

type ObjectStore interface {
	Put(context.Context, string, []byte, string) error
	Read(context.Context, string) ([]byte, bool, error)
	Delete(context.Context, string) error
	Prune(context.Context, map[string]bool, time.Time) (int, error)
}
type Attachments struct {
	Directory  string
	Maximum    int64
	Remote     ObjectStore
	Pool       *database.Pool
	ExtractPDF func(context.Context, []byte) (string, error)
	mu         sync.Mutex
}

var attachmentID = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func attachmentMissing() error { return api.NewBusinessError(api.ResourceNotFound, "附件不存在") }
func (a *Attachments) root(create bool) (*os.Root, error) {
	if create {
		if e := os.MkdirAll(a.Directory, 0700); e != nil {
			return nil, e
		}
	}
	return os.OpenRoot(a.Directory)
}
func (a *Attachments) Store(ctx context.Context, data []byte, filename, contentType *string) (Attachment, error) {
	if len(data) == 0 {
		return Attachment{}, invalid("文件为空")
	}
	if int64(len(data)) > a.Maximum {
		return Attachment{}, invalid("文件不能超过 " + describeLimit(a.Maximum))
	}
	id := agent.UUID()
	if filename == nil {
		filename = &id
	}
	if contentType == nil {
		contentType = ptr("application/octet-stream")
	}
	if a.Remote != nil {
		if e := a.Remote.Put(ctx, id, data, *contentType); e != nil {
			return Attachment{}, e
		}
	} else {
		root, e := a.root(true)
		if e != nil {
			return Attachment{}, e
		}
		defer root.Close()
		f, e := root.OpenFile(id, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
		if e != nil {
			return Attachment{}, e
		}
		_, e = f.Write(data)
		closeErr := f.Close()
		if e = errors.Join(e, closeErr, ctx.Err()); e != nil {
			_ = root.Remove(id)
			return Attachment{}, e
		}
	}
	return Attachment{ID: &id, Filename: filename, ContentType: contentType, Size: int64(len(data))}, nil
}
func (a *Attachments) Read(ctx context.Context, id string) ([]byte, error) {
	if !attachmentID.MatchString(id) {
		return nil, attachmentMissing()
	}
	root, e := a.root(false)
	if e == nil {
		defer root.Close()
		info, e := root.Lstat(id)
		if e == nil && !info.Mode().IsRegular() {
			return nil, attachmentMissing()
		}
		if e == nil && info.Mode().IsRegular() {
			f, e := root.Open(id)
			if e != nil {
				return nil, attachmentMissing()
			}
			defer f.Close()
			opened, statErr := f.Stat()
			if statErr != nil || !opened.Mode().IsRegular() || !os.SameFile(info, opened) {
				return nil, attachmentMissing()
			}
			data, e := io.ReadAll(io.LimitReader(f, a.Maximum+1))
			if e != nil {
				return nil, e
			}
			if int64(len(data)) > a.Maximum {
				return nil, invalid("对象超出附件大小上限")
			}
			return data, ctx.Err()
		}
	}
	if a.Remote != nil {
		data, found, e := a.Remote.Read(ctx, id)
		if e != nil {
			return nil, e
		}
		if found {
			if int64(len(data)) > a.Maximum {
				return nil, invalid("对象超出附件大小上限")
			}
			return data, nil
		}
	}
	return nil, attachmentMissing()
}
func (a *Attachments) Verify(ctx context.Context, declared []Attachment) ([]Attachment, error) {
	out := []Attachment{}
	for _, v := range declared {
		if !attachmentID.MatchString(value(v.ID)) {
			continue
		}
		data, e := a.Read(ctx, *v.ID)
		if e != nil {
			var be *api.BusinessError
			if errors.As(e, &be) && be.Code() == api.ResourceNotFound {
				continue
			}
			return nil, e
		}
		v.Size = int64(len(data))
		out = append(out, v)
	}
	return out, nil
}

// 同一数据库内，绑定事务持共享锁；GC 持独占锁直到扫描和物理删除结束。
// 使用事务锁，不占用第二条连接，也不把对象存储当作数据库事实来源。
const attachmentLock int64 = 0x5041494f5346494c

func (a *Attachments) lockBinding(ctx context.Context, tx *sql.Tx) error {
	_, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock_shared($1)`, attachmentLock)
	return err
}

func attachmentReferences(ctx context.Context, tx *sql.Tx) (map[string]bool, error) {
	refs := map[string]bool{}
	rows, err := tx.QueryContext(ctx, `SELECT attachments FROM chat_message WHERE attachments IS NOT NULL UNION ALL SELECT request FROM chat_run UNION ALL SELECT request FROM agent_followup WHERE status='PENDING'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw string
		if err = rows.Scan(&raw); err != nil {
			return nil, err
		}
		var list []Attachment
		if strings.HasPrefix(strings.TrimSpace(raw), "[") {
			err = json.Unmarshal([]byte(raw), &list)
		} else {
			var v struct {
				Attachments []Attachment `json:"attachments"`
			}
			err = json.Unmarshal([]byte(raw), &v)
			list = v.Attachments
		}
		if err != nil {
			return nil, errors.New("附件引用快照损坏，停止清理")
		}
		for _, v := range list {
			if v.ID != nil {
				refs[*v.ID] = true
			}
		}
	}
	return refs, rows.Err()
}

// References 同时保护消息和仍可能恢复/排队的请求；读取失败时绝不开始删除。
func (a *Attachments) References(ctx context.Context) (refs map[string]bool, err error) {
	err = a.Pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		var e error
		refs, e = attachmentReferences(ctx, tx)
		return e
	})
	return
}
func (a *Attachments) collect(ctx context.Context, action func(map[string]bool) error) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	// READ COMMITTED 在获得独占锁后读取最新已提交引用，不能使用旧快照。
	return a.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock($1)`, attachmentLock); err != nil {
			return err
		}
		refs, err := attachmentReferences(ctx, tx)
		if err != nil {
			return err
		}
		return action(refs)
	})
}
func (a *Attachments) delete(ctx context.Context, id string) {
	if !attachmentID.MatchString(id) {
		return
	}
	if a.Remote != nil {
		_ = a.Remote.Delete(ctx, id)
	}
	if root, e := a.root(false); e == nil {
		defer root.Close()
		_ = root.Remove(id)
	}
}
func (a *Attachments) Cleanup(ctx context.Context, files []Attachment) {
	if len(files) == 0 {
		return
	}
	_ = a.collect(ctx, func(refs map[string]bool) error {
		for _, f := range files {
			if f.ID != nil && !refs[*f.ID] {
				a.delete(ctx, *f.ID)
			}
		}
		return nil
	})
}
func (a *Attachments) Prune(ctx context.Context, now time.Time) (removed int, err error) {
	err = a.collect(ctx, func(refs map[string]bool) error {
		if a.Remote != nil {
			n, e := a.Remote.Prune(ctx, refs, now.Add(-24*time.Hour))
			removed += n
			if e != nil {
				return e
			}
		}
		root, e := a.root(false)
		if errors.Is(e, os.ErrNotExist) {
			return nil
		}
		if e != nil {
			return e
		}
		defer root.Close()
		dir, e := root.Open(".")
		if e != nil {
			return e
		}
		defer dir.Close()
		entries, e := dir.ReadDir(-1)
		if e != nil {
			return e
		}
		for _, entry := range entries {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			id := entry.Name()
			if !attachmentID.MatchString(id) || refs[id] {
				continue
			}
			info, e := root.Lstat(id)
			if e != nil || !info.Mode().IsRegular() || info.ModTime().After(now.Add(-time.Hour)) {
				continue
			}
			if root.Remove(id) == nil {
				removed++
			}
		}
		return nil
	})
	return
}

type AttachmentContext struct {
	Images   []model.Image
	Text     string
	Verified []Attachment
}

func (a *Attachments) Build(ctx context.Context, declared []Attachment) (v AttachmentContext, err error) {
	v.Images = []model.Image{}
	v.Verified, err = a.Verify(ctx, declared)
	if err != nil || len(v.Verified) == 0 {
		return
	}
	notes := []string{}
	var block strings.Builder
	for _, file := range v.Verified {
		filename := value(file.Filename)
		typ := value(file.ContentType)
		data, e := a.Read(ctx, value(file.ID))
		if e != nil {
			notes = append(notes, filename+"：读取失败，本轮未使用")
			continue
		}
		if strings.HasPrefix(typ, "image/") {
			if len(v.Images) >= 4 {
				notes = append(notes, filename+"：本轮图片数量已达上限（4 张），未发送")
				continue
			}
			if len(data) > 5*1024*1024 {
				notes = append(notes, filename+"：图片超过 5MB，未发送（可压缩后重试）")
				continue
			}
			if _, _, e = mime.ParseMediaType(typ); e != nil {
				notes = append(notes, filename+"：无法识别图片类型，未发送")
				continue
			}
			v.Images = append(v.Images, model.Image{MIME: typ, Data: data})
			continue
		}
		if chatcontext.JavaLength(block.String()) >= 16000 {
			notes = append(notes, filename+"：本轮文本附件已达上限，未发送")
			continue
		}
		text, ok := a.extract(ctx, data, filename, typ)
		if !ok {
			notes = append(notes, filename+"：格式不支持直接读取（当前支持 txt/md/json/csv/xml/yaml/pdf/docx）")
			continue
		}
		block.WriteString("\n### " + filename + "\n")
		if chatcontext.JavaLength(text) > 8000 {
			block.WriteString(chatcontext.JavaSubstring(text, 0, 8000))
			block.WriteString("\n（以上内容已截断，原文更长）")
		} else {
			block.WriteString(text)
		}
		block.WriteByte('\n')
	}
	if len(v.Images) == 0 && block.Len() == 0 {
		if len(notes) > 0 {
			v.Text = "【用户上传的附件】\n" + strings.Join(notes, "\n")
		}
		return
	}
	v.Text = "【用户上传的附件】"
	if block.Len() > 0 {
		v.Text += "\n" + block.String()
	}
	for _, note := range notes {
		v.Text += "\n- " + note
	}
	return
}
func (a *Attachments) extract(ctx context.Context, data []byte, filename, typ string) (text string, ok bool) {
	defer func() {
		if recover() != nil {
			text = ""
			ok = false
		}
	}()
	typ = strings.ToLower(typ)
	name := strings.ToLower(filename)
	plain := strings.HasPrefix(typ, "text/") || typ == "application/json" || typ == "application/xml" || typ == "application/yaml" || typ == "application/x-yaml"
	for _, ext := range []string{".md", ".txt", ".json", ".csv", ".log", ".yaml", ".yml", ".xml"} {
		plain = plain || strings.HasSuffix(name, ext)
	}
	if plain {
		text = strings.ToValidUTF8(string(data), "�")
	} else if strings.HasSuffix(name, ".docx") || strings.Contains(typ, "wordprocessingml") {
		reader, e := zip.NewReader(bytes.NewReader(data), int64(len(data)))
		if e != nil {
			return "", false
		}
		for _, f := range reader.File {
			if f.Name != "word/document.xml" {
				continue
			}
			if f.UncompressedSize64 > 32<<20 {
				return "", false
			}
			r, e := f.Open()
			if e != nil {
				return "", false
			}
			defer r.Close()
			decoder := xml.NewDecoder(io.LimitReader(r, 32<<20))
			var b strings.Builder
			inText := false
			for {
				tok, e := decoder.Token()
				if e == io.EOF {
					break
				}
				if e != nil {
					return "", false
				}
				switch t := tok.(type) {
				case xml.StartElement:
					if t.Name.Local == "t" {
						inText = true
					}
					if t.Name.Local == "tab" {
						b.WriteByte('\t')
					}
					if t.Name.Local == "br" {
						b.WriteByte('\n')
					}
				case xml.EndElement:
					if t.Name.Local == "t" {
						inText = false
					}
					if t.Name.Local == "p" {
						b.WriteByte('\n')
					}
				case xml.CharData:
					if inText {
						b.Write(t)
					}
				}
				if b.Len() > 64000 {
					break
				}
			}
			text = b.String()
			break
		}
	} else if (strings.HasSuffix(name, ".pdf") || typ == "application/pdf") && a.ExtractPDF != nil {
		var e error
		text, e = a.ExtractPDF(ctx, data)
		if e != nil {
			return "", false
		}
	} else {
		return "", false
	}
	text = strip(text)
	return text, text != ""
}
func NewAttachments(directory string, maximum int64, pool *database.Pool, remote ObjectStore) (*Attachments, error) {
	if directory == "" || maximum <= 0 || maximum >= 64<<20 || pool == nil {
		return nil, errors.New("附件配置无效")
	}
	directory, e := filepath.Abs(directory)
	if e != nil {
		return nil, e
	}
	return &Attachments{Directory: directory, Maximum: maximum, Pool: pool, Remote: remote}, nil
}
