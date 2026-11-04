package memory

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/database"
	"regexp"
)

type Entry struct {
	ID           string     `json:"id"`
	Content      string     `json:"content"`
	Source       string     `json:"source"`
	Pinned       bool       `json:"pinned"`
	SupersededBy *string    `json:"supersededBy"`
	CreatedAt    time.Time  `json:"createdAt"`
	UpdatedAt    time.Time  `json:"updatedAt"`
	HitCount     int        `json:"hitCount"`
	LastUsedAt   *time.Time `json:"lastUsedAt"`
}

type Store struct {
	pool      *database.Pool
	embedding model.EmbeddingModel
	modelTag  string
	threshold float64
}

func NewStore(ctx context.Context, pool *database.Pool) (*Store, error) {
	err := pool.WithConn(ctx, func(conn *sql.Conn) error {
		var allowed bool
		err := conn.QueryRowContext(ctx, `SELECT bool_and(has_table_privilege(current_user, 'public.memory', privilege))
			FROM unnest(ARRAY['SELECT','INSERT','UPDATE','DELETE']) AS permissions(privilege)`).Scan(&allowed)
		if err != nil {
			return err
		}
		if !allowed {
			return errors.New("P5运行账号缺少记忆表权限")
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return &Store{pool: pool}, nil
}

const columns = "id::text,content,source,pinned,superseded_by::text,created_at,updated_at,hit_count,last_used_at"

func scan(row interface{ Scan(...any) error }, entry *Entry) error {
	err := row.Scan(&entry.ID, &entry.Content, &entry.Source, &entry.Pinned, &entry.SupersededBy,
		&entry.CreatedAt, &entry.UpdatedAt, &entry.HitCount, &entry.LastUsedAt)
	entry.CreatedAt = entry.CreatedAt.UTC()
	entry.UpdatedAt = entry.UpdatedAt.UTC()
	if entry.LastUsedAt != nil {
		at := entry.LastUsedAt.UTC()
		entry.LastUsedAt = &at
	}
	return err
}

func (s *Store) List(ctx context.Context) ([]Entry, error) {
	entries := []Entry{}
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		rows, err := conn.QueryContext(ctx, "SELECT "+columns+" FROM memory ORDER BY pinned DESC,updated_at DESC")
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var entry Entry
			if err := scan(rows, &entry); err != nil {
				return err
			}
			entries = append(entries, entry)
		}
		return rows.Err()
	})
	return entries, err
}

func strip(value string) string {
	return strings.TrimFunc(value, func(r rune) bool {
		return unicode.IsSpace(r) && r != '\u0085' && r != '\u00a0' && r != '\u2007' && r != '\u202f' || r >= '\x1c' && r <= '\x1f'
	})
}

func normalize(content string) (string, error) {
	content = strip(content)
	if content == "" {
		return "", api.NewBusinessError(api.ValidationFailed, "记忆内容不能为空")
	}
	if len(utf16.Encode([]rune(content))) > 2000 {
		return "", api.NewBusinessError(api.ValidationFailed, "记忆内容长度不能超过 2000")
	}
	return content, nil
}

func contentHash(content string) string {
	digest := sha256.Sum256([]byte(content))
	return hex.EncodeToString(digest[:])
}

func (s *Store) Create(ctx context.Context, content string) (Entry, error) {
	return s.create(ctx, content, "MANUAL")
}

func (s *Store) CreateAuto(ctx context.Context, content string) (Entry, error) {
	return s.create(ctx, content, "AUTO")
}

func (s *Store) create(ctx context.Context, content, source string) (Entry, error) {
	content, err := normalize(content)
	if err != nil {
		return Entry{}, err
	}
	hash := contentHash(content)
	var entry Entry
	err = s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return scan(conn.QueryRowContext(ctx, "SELECT "+columns+" FROM memory WHERE content_hash=$1", hash), &entry)
	})
	if !errors.Is(err, sql.ErrNoRows) {
		return entry, err
	}
	vector := s.vector(ctx, content, 10*time.Second)
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if err := lockVectors(ctx, tx, vector); err != nil {
			return err
		}
		now := time.Now().UTC()
		err := scan(tx.QueryRowContext(ctx, `INSERT INTO memory(id,content,content_hash,source,created_at,updated_at)
			VALUES(gen_random_uuid(),$1,$2,$3,$4,$4) ON CONFLICT(content_hash) DO NOTHING RETURNING `+columns,
			content, hash, source, now), &entry)
		if errors.Is(err, sql.ErrNoRows) {
			// A separate statement sees the winning transaction under READ COMMITTED.
			return scan(tx.QueryRowContext(ctx, "SELECT "+columns+" FROM memory WHERE content_hash=$1", hash), &entry)
		}
		if err != nil {
			return err
		}
		return s.applyVector(ctx, tx, &entry, vector)
	})
	return entry, err
}

var uuidPattern = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

func parseID(raw string) (string, error) {
	if !uuidPattern.MatchString(raw) {
		return "", api.NewBusinessError(api.ValidationFailed, "记忆标识格式非法")
	}
	return raw, nil
}

func missing(raw string, err error) error {
	if errors.Is(err, sql.ErrNoRows) {
		return api.NewBusinessError(api.ResourceNotFound, fmt.Sprintf("记忆不存在：%s", raw))
	}
	return err
}

func (s *Store) Update(ctx context.Context, raw, content string) (Entry, error) {
	id, err := parseID(raw)
	if err != nil {
		return Entry{}, err
	}
	content, err = normalize(content)
	if err != nil {
		return Entry{}, err
	}
	vector := s.vector(ctx, content, 10*time.Second)
	var entry Entry
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if err := lockVectors(ctx, tx, vector); err != nil {
			return err
		}
		err := scan(tx.QueryRowContext(ctx, `UPDATE memory SET content=$2,content_hash=$3,updated_at=$4,
			embedding=NULL,embedding_model=NULL,embedded_at=NULL WHERE id=$1 RETURNING `+columns,
			id, content, contentHash(content), time.Now().UTC()), &entry)
		if err != nil {
			return missing(raw, err)
		}
		return s.applyVector(ctx, tx, &entry, vector)
	})
	var dbError interface{ SQLState() string }
	if errors.As(err, &dbError) && dbError.SQLState() == "23505" {
		return Entry{}, api.NewBusinessError(api.BusinessConflict, "已存在内容相同的记忆，请直接修改那条或取消本次编辑")
	}
	return entry, err
}

func (s *Store) Pin(ctx context.Context, raw string, pinned bool) (Entry, error) {
	id, err := parseID(raw)
	if err != nil {
		return Entry{}, err
	}
	return s.change(ctx, raw, "UPDATE memory SET pinned=$2 WHERE id=$1 RETURNING "+columns, id, pinned)
}

func (s *Store) Delete(ctx context.Context, raw string) error {
	id, err := parseID(raw)
	if err != nil {
		return err
	}
	_, err = s.change(ctx, raw, "DELETE FROM memory WHERE id=$1 RETURNING "+columns, id)
	return err
}

func (s *Store) change(ctx context.Context, raw, query string, args ...any) (Entry, error) {
	var entry Entry
	err := s.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return scan(conn.QueryRowContext(ctx, query, args...), &entry)
	})
	return entry, missing(raw, err)
}
