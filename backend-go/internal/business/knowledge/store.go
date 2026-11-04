package knowledge

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
	"time"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
)

type Document struct {
	ID          string    `json:"id"`
	Title       string    `json:"title"`
	Filename    string    `json:"filename"`
	ContentType string    `json:"contentType"`
	SizeBytes   int64     `json:"sizeBytes"`
	CharCount   int       `json:"charCount"`
	ChunkCount  int       `json:"chunkCount"`
	CreatedAt   time.Time `json:"createdAt"`
}
type Chunk struct {
	ID, Content string
	Metadata    map[string]any
	Vector      []float32
}
type Store struct {
	Pool      *database.Pool
	Reader    Reader
	Embedding model.EmbeddingModel
	Locks     *coordination.Coordinator
	table     string
}

// VectorTable returns only the identifier validated by NewStore; callers cannot inject SQL names.
func (s *Store) VectorTable() string { return s.table }

var safeIdentifier = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`)

func NewStore(ctx context.Context, pool *database.Pool, reader Reader, embedding model.EmbeddingModel, locks *coordination.Coordinator, schema, table string) (*Store, error) {
	if pool == nil || locks == nil || !safeIdentifier.MatchString(schema) || !safeIdentifier.MatchString(table) {
		return nil, errors.New("知识仓储需要数据库、协调器和合法表名")
	}
	s := &Store{pool, reader, embedding, locks, schema + "." + table}
	err := pool.WithConn(ctx, func(c *sql.Conn) error {
		var typ string
		if e := c.QueryRowContext(ctx, `SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relname=$1 AND n.nspname=$2 AND a.attname='embedding' AND a.attnum>0 AND NOT a.attisdropped`, table, schema).Scan(&typ); e != nil {
			return e
		}
		if typ != fmt.Sprintf("vector(%d)", reader.Properties.EmbeddingDimensions) {
			return api.NewBusinessError(api.EmbeddingDimensionMismatch, "嵌入模型维度配置与向量表结构不一致")
		}
		var wrong int
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM "+s.table+" WHERE vector_dims(embedding)<>$1", reader.Properties.EmbeddingDimensions).Scan(&wrong); e != nil {
			return e
		}
		if wrong != 0 {
			return errors.New("知识向量包含错误维度")
		}
		return nil
	})
	return s, err
}
func newID() string {
	var b [16]byte
	if _, e := rand.Read(b[:]); e != nil {
		panic(e)
	}
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
}

const documentColumns = "id::text,title,filename,content_type,size_bytes,char_count,chunk_count,created_at"

type scanner interface{ Scan(...any) error }

func scanDocument(row scanner, d *Document) error {
	e := row.Scan(&d.ID, &d.Title, &d.Filename, &d.ContentType, &d.SizeBytes, &d.CharCount, &d.ChunkCount, &d.CreatedAt)
	d.CreatedAt = d.CreatedAt.UTC()
	return e
}
func missingDocument(err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return api.NewBusinessError(api.ResourceNotFound, "知识文档不存在")
	}
	return err
}
func (s *Store) Document(ctx context.Context, id string) (Document, error) {
	var d Document
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return scanDocument(c.QueryRowContext(ctx, "SELECT "+documentColumns+" FROM knowledge_document WHERE id=$1", id), &d)
	})
	return d, missingDocument(e)
}
func (s *Store) List(ctx context.Context) ([]Document, error) {
	out := []Document{}
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		rows, e := c.QueryContext(ctx, "SELECT "+documentColumns+" FROM knowledge_document ORDER BY created_at DESC")
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var d Document
			if e = scanDocument(rows, &d); e != nil {
				return e
			}
			out = append(out, d)
		}
		return rows.Err()
	})
	return out, e
}
func LikePattern(s string) string {
	return "%" + strings.NewReplacer("\\", "\\\\", "%", "\\%", "_", "\\_").Replace(strings.ToLower(strip(s))) + "%"
}
func (s *Store) SearchDocuments(ctx context.Context, keyword string, page, size int) (api.Page[Document], error) {
	page = max(page, 0)
	if size <= 0 {
		size = 20
	}
	size = min(size, 100)
	out := []Document{}
	var total int64
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		where := " WHERE lower(title) LIKE $1 ESCAPE '\\' OR lower(filename) LIKE $1 ESCAPE '\\'"
		pattern := LikePattern(keyword)
		if e := c.QueryRowContext(ctx, "SELECT count(*) FROM knowledge_document"+where, pattern).Scan(&total); e != nil {
			return e
		}
		if int64(page)*int64(size) > math.MaxInt32 {
			return nil
		}
		rows, e := c.QueryContext(ctx, "SELECT "+documentColumns+" FROM knowledge_document"+where+" ORDER BY created_at DESC LIMIT $2 OFFSET $3", pattern, size, int64(page)*int64(size))
		if e != nil {
			return e
		}
		defer rows.Close()
		for rows.Next() {
			var d Document
			if e = scanDocument(rows, &d); e != nil {
				return e
			}
			out = append(out, d)
		}
		return rows.Err()
	})
	return api.PageOfResult(out, page, size, total), e
}
func vectorText(vector []float32) (string, error) {
	values := make([]string, len(vector))
	for i, v := range vector {
		if math.IsNaN(float64(v)) || math.IsInf(float64(v), 0) {
			return "", errors.New("嵌入结果不是有限数值")
		}
		values[i] = strconv.FormatFloat(float64(v), 'g', -1, 32)
	}
	return "[" + strings.Join(values, ",") + "]", nil
}
func (s *Store) PrepareChunks(ctx context.Context, upload Upload, id string) ([]Chunk, error) {
	blocks := SplitMarkdown(upload.Text, s.Reader.Properties.ChunkSizeChars, s.Reader.Properties.MinChunkSizeChars, s.Reader.Properties.ChunkOverlapChars)
	if len(blocks) == 0 {
		return nil, unsupported("解析后没有可索引的内容")
	}
	return s.prepareTextChunks(ctx, blocks, id, upload.Title, upload.Filename)
}
func (s *Store) prepareTextChunks(ctx context.Context, blocks []string, id, title, filename string) ([]Chunk, error) {
	if s.Embedding == nil {
		return nil, api.NewBusinessError(api.AINotConfigured, api.AINotConfigured.Message())
	}
	chunks := make([]Chunk, len(blocks))
	for i, text := range blocks {
		chunks[i] = Chunk{ID: newID(), Content: text, Metadata: map[string]any{"document_id": id, "document_title": title, "filename": filename, "chunk_index": i}}
	}
	// 限制单次模型批量；全部嵌入准备完成之后才进入最终写事务。
	for start := 0; start < len(blocks); start += 100 {
		end := min(start+100, len(blocks))
		output, e := s.Embedding.Embed(ctx, model.EmbeddingRequest{Input: blocks[start:end]})
		if e != nil {
			return nil, api.NewBusinessError(api.AIUpstreamError, "无法连接嵌入模型服务，请确认模型服务已启动")
		}
		if len(output.Results) != end-start {
			return nil, errors.New("向量数量不匹配")
		}
		seen := map[int]bool{}
		for _, v := range output.Results {
			if v.Index < 0 || v.Index >= end-start || seen[v.Index] {
				return nil, errors.New("向量索引无效")
			}
			seen[v.Index] = true
			if len(v.Vector) != s.Reader.Properties.EmbeddingDimensions {
				return nil, api.NewBusinessError(api.EmbeddingDimensionMismatch, api.EmbeddingDimensionMismatch.Message())
			}
			if _, e = vectorText(v.Vector); e != nil {
				return nil, e
			}
			chunks[start+v.Index].Vector = v.Vector
		}
	}
	return chunks, nil
}
func (s *Store) insertChunks(ctx context.Context, tx *sql.Tx, chunks []Chunk) error {
	stmt, e := tx.PrepareContext(ctx, "INSERT INTO "+s.table+"(id,content,metadata,embedding) VALUES($1,$2,$3::json,$4::vector)")
	if e != nil {
		return e
	}
	defer stmt.Close()
	for _, chunk := range chunks {
		metadata, e := json.Marshal(chunk.Metadata)
		if e != nil {
			return e
		}
		v, e := vectorText(chunk.Vector)
		if e != nil {
			return e
		}
		if _, e = stmt.ExecContext(ctx, chunk.ID, chunk.Content, string(metadata), v); e != nil {
			return e
		}
	}
	return nil
}
func (s *Store) persist(ctx context.Context, tx *sql.Tx, id string, replacing bool, u Upload, chunks []Chunk) (Document, error) {
	var d Document
	var e error
	if replacing {
		e = scanDocument(tx.QueryRowContext(ctx, `UPDATE knowledge_document SET title=$2,filename=$3,content_type=$4,size_bytes=$5,char_count=$6,chunk_count=$7,updated_at=now() WHERE id=$1 RETURNING `+documentColumns, id, u.Title, u.Filename, u.ContentType, u.SizeBytes, u.CharCount, len(chunks)), &d)
		if e != nil {
			return d, missingDocument(e)
		}
		_, e = tx.ExecContext(ctx, "DELETE FROM "+s.table+" WHERE metadata->>'document_id'=$1", id)
	} else {
		e = scanDocument(tx.QueryRowContext(ctx, `INSERT INTO knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count) VALUES($1,$2,$3,$4,$5,$6,$7) RETURNING `+documentColumns, id, u.Title, u.Filename, u.ContentType, u.SizeBytes, u.CharCount, len(chunks)), &d)
	}
	if e == nil {
		e = s.insertChunks(ctx, tx, chunks)
	}
	return d, e
}
func (s *Store) Ingest(ctx context.Context, filename, title string, data []byte) (Document, error) {
	u, e := s.Reader.Prepare(ctx, filename, title, data)
	if e != nil {
		return Document{}, e
	}
	id := newID()
	chunks, e := s.PrepareChunks(ctx, u, id)
	if e != nil {
		return Document{}, e
	}
	var d Document
	e = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error { d, e = s.persist(ctx, tx, id, false, u, chunks); return e })
	return d, e
}
func (s *Store) lock(ctx context.Context, id string) (*coordination.Lease, error) {
	l, e := s.Locks.Acquire(ctx, "document:"+id, 1)
	if e == nil && l == nil {
		e = api.NewBusinessError(api.BusinessConflict, "文档正在写入，请稍后重试")
	}
	return l, e
}
func release(l *coordination.Lease) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = l.Release(ctx)
}
func (s *Store) Update(ctx context.Context, id, filename string, data []byte) (Document, error) {
	l, e := s.lock(ctx, id)
	if e != nil {
		return Document{}, e
	}
	defer release(l)
	if _, e = s.Document(ctx, id); e != nil {
		return Document{}, e
	}
	u, e := s.Reader.Prepare(ctx, filename, titleOf(SanitizeFilename(filename)), data)
	if e != nil {
		return Document{}, e
	}
	chunks, e := s.PrepareChunks(ctx, u, id)
	if e != nil {
		return Document{}, e
	}
	var d Document
	e = s.Pool.WithManagedTx(ctx, sql.TxOptions{}, func(tx *database.Transaction) error {
		if e := l.Fence(ctx, tx); e != nil {
			return e
		}
		d, e = s.persist(ctx, tx.SQL(), id, true, u, chunks)
		return e
	})
	return d, e
}
func (s *Store) Delete(ctx context.Context, id string) (int, error) {
	l, e := s.lock(ctx, id)
	if e != nil {
		return 0, e
	}
	defer release(l)
	deleted := 0
	e = s.Pool.WithManagedTx(ctx, sql.TxOptions{}, func(tx *database.Transaction) error {
		if e := l.Fence(ctx, tx); e != nil {
			return e
		}
		var exists string
		if e := tx.SQL().QueryRowContext(ctx, "SELECT id::text FROM knowledge_document WHERE id=$1 FOR UPDATE", id).Scan(&exists); e != nil {
			return missingDocument(e)
		}
		res, e := tx.SQL().ExecContext(ctx, "DELETE FROM "+s.table+" WHERE metadata->>'document_id'=$1", id)
		if e != nil {
			return e
		}
		n, e := res.RowsAffected()
		if e != nil {
			return e
		}
		deleted = int(n)
		_, e = tx.SQL().ExecContext(ctx, "DELETE FROM knowledge_document WHERE id=$1", id)
		return e
	})
	return deleted, e
}
