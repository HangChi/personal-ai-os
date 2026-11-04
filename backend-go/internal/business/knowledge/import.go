package knowledge

import (
	"context"
	"database/sql"
	"personal-ai-os/backend-go/internal/platform/api"
	"time"
)

type ImportDocumentMeta struct {
	ID          *string    `json:"id"`
	Title       *string    `json:"title"`
	Filename    *string    `json:"filename"`
	ContentType *string    `json:"contentType"`
	SizeBytes   int64      `json:"sizeBytes"`
	CharCount   *int       `json:"charCount"`
	CreatedAt   *time.Time `json:"createdAt"`
}
type ImportDocument struct {
	Document *ImportDocumentMeta `json:"document"`
	Chunks   []string            `json:"chunks"`
	Source   *SourceInfo         `json:"source"`
}
type PreparedImport struct {
	meta                             ImportDocumentMeta
	id, title, filename, contentType string
	chars                            int
	chunks                           []Chunk
	source                           *SourceInfo
}

func (s *Store) PrepareImport(ctx context.Context, documents []*ImportDocument) ([]PreparedImport, error) {
	out := []PreparedImport{}
	for _, entry := range documents {
		if entry == nil {
			return nil, invalid("文档记录无效")
		}
		m := entry.Document
		if m == nil {
			continue
		}
		id := ""
		if m.ID != nil {
			var ok bool
			id, ok = api.ParseJavaUUID(*m.ID)
			if !ok {
				return nil, invalid("文档标识格式非法")
			}
		}
		if id == "" || len(entry.Chunks) == 0 {
			continue
		}
		title, filename, contentType := "", "imported.md", "text/markdown"
		if m.Filename != nil {
			title = *m.Filename
			if strip(*m.Filename) != "" {
				filename = *m.Filename
			}
		}
		if m.Title != nil && strip(*m.Title) != "" {
			title = *m.Title
		}
		if m.ContentType != nil && strip(*m.ContentType) != "" {
			contentType = *m.ContentType
		}
		texts := []string{}
		charCount := 0
		for _, text := range entry.Chunks {
			if strip(text) != "" {
				texts = append(texts, text)
				charCount += chars(text)
			}
		}
		chunks := []Chunk{}
		var e error
		if len(texts) > 0 {
			chunks, e = s.prepareTextChunks(ctx, texts, id, title, filename)
			if e != nil {
				return nil, e
			}
		}
		if m.CharCount != nil {
			charCount = *m.CharCount
		}
		out = append(out, PreparedImport{*m, id, title, filename, contentType, charCount, chunks, entry.Source})
	}
	return out, nil
}
func (s *Store) ImportPrepared(ctx context.Context, tx *sql.Tx, documents []PreparedImport) (int, int, error) {
	count, vectors := 0, 0
	for _, d := range documents {
		created := time.Now().UTC()
		if d.meta.CreatedAt != nil {
			created = *d.meta.CreatedAt
		}
		// Missing title and filename remains a validation error rather than inventing imported content.
		var title any = d.title
		if d.title == "" && d.meta.Filename == nil {
			title = nil
		}
		_, e := tx.ExecContext(ctx, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$8)`, d.id, title, d.filename, d.contentType, d.meta.SizeBytes, d.chars, len(d.chunks), created)
		if e != nil {
			return 0, 0, e
		}
		if e = s.insertChunks(ctx, tx, d.chunks); e != nil {
			return 0, 0, e
		}
		if d.source != nil {
			source := d.source
			name := strip(source.SpaceName)
			if name == "" || chars(name) > 120 {
				return 0, 0, invalid("知识库名称需为 1 至 120 字符")
			}
			var spaceID string
			if e = tx.QueryRowContext(ctx, `INSERT INTO knowledge_space(name) VALUES($1) ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id::text`, name).Scan(&spaceID); e != nil {
				return 0, 0, e
			}
			m := Metadata{SpaceID: spaceID, Version: source.Version, Scope: source.Scope, EffectiveFrom: source.EffectiveFrom, EffectiveUntil: source.EffectiveUntil, Authority: source.Authority, Lifecycle: source.Lifecycle}
			if e = validateMetadata(m); e != nil {
				return 0, 0, e
			}
			if _, e = tx.ExecContext(ctx, `UPDATE knowledge_source SET space_id=$1,version=$2,scope=$3,effective_from=$4::date,effective_until=$5::date,authority=$6,lifecycle=$7 WHERE document_id=$8`, spaceID, strip(m.Version), strip(m.Scope), m.EffectiveFrom, m.EffectiveUntil, strip(m.Authority), m.Lifecycle, d.id); e != nil {
				return 0, 0, e
			}
		}
		count++
		vectors += len(d.chunks)
	}
	return count, vectors, nil
}
