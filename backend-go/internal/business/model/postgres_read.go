package model

import (
	"context"
	"database/sql"
	"errors"

	"personal-ai-os/backend-go/internal/platform/database"
)

// PostgresReadStore 只读取原 V20–V25/V29 表；不创建或修改 schema，不写入凭据。
type PostgresReadStore struct {
	pool *database.Pool
	keys *KeyCodec
}

func NewPostgresReadStore(pool *database.Pool, keys *KeyCodec) (*PostgresReadStore, error) {
	if pool == nil || keys == nil {
		return nil, errors.New("模型仓储需要连接池与显式密钥转换器")
	}
	return &PostgresReadStore{pool: pool, keys: keys}, nil
}

type scanner interface{ Scan(...any) error }

func (s *PostgresReadStore) scanValues(row scanner, v *Values) error {
	if err := row.Scan(&v.BaseURL, &v.APIKey, &v.ChatModel, &v.Temperature, &v.EmbeddingModel, &v.AudioModel, &v.Revision, &v.UpdatedAt); err != nil {
		return err
	}
	key, err := s.keys.Decrypt(v.APIKey)
	// 解密失败也不向调用方返回携带原密文的半份配置。
	if err != nil {
		*v = Values{}
		return err
	}
	v.APIKey = key
	return nil
}

func (s *PostgresReadStore) Legacy(ctx context.Context) (v Values, found bool, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		return s.scanValues(c.QueryRowContext(ctx, `SELECT base_url,api_key,chat_model,temperature,embedding_model,NULL::text,revision,updated_at FROM ai_model_config WHERE id=1`), &v)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Values{}, false, nil
	}
	if err != nil {
		return Values{}, false, err
	}
	return v, true, nil
}

func (s *PostgresReadStore) Current(ctx context.Context) (v Values, err error) {
	err = s.pool.WithConn(ctx, func(c *sql.Conn) error {
		// 单语句快照避免 active 检查与 V20 回落之间发生切换；只解密真正被选中的配置。
		return s.scanValues(c.QueryRowContext(ctx, `SELECT base_url,api_key,chat_model,temperature,embedding_model,audio_model,revision,updated_at
 FROM ai_model_profile WHERE active
 UNION ALL
 SELECT base_url,api_key,chat_model,temperature,embedding_model,NULL::text,revision,updated_at
 FROM ai_model_config WHERE id=1 AND NOT EXISTS(SELECT 1 FROM ai_model_profile WHERE active)`), &v)
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Values{}, nil
	}
	if err != nil {
		return Values{}, err
	}
	return v, nil
}

func (s *PostgresReadStore) Profiles(ctx context.Context) ([]Profile, error) {
	profiles := make([]Profile, 0)
	err := s.pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT id::text,name,preset_id,base_url,api_key,chat_model,temperature,embedding_model,audio_model,protocol,active,sort_order,revision,created_at,updated_at
 FROM ai_model_profile ORDER BY sort_order,created_at`)
		if err != nil {
			return err
		}
		defer rows.Close()
		index := make(map[string]int)
		for rows.Next() {
			p := Profile{Models: make([]Model, 0)}
			if err := rows.Scan(&p.ID, &p.Name, &p.PresetID, &p.BaseURL, &p.APIKey, &p.ChatModel, &p.Temperature, &p.EmbeddingModel, &p.AudioModel, &p.Protocol, &p.Active, &p.SortOrder, &p.Revision, &p.CreatedAt, &p.UpdatedAt); err != nil {
				return err
			}
			key, err := s.keys.Decrypt(p.APIKey)
			if err != nil {
				return err
			}
			p.APIKey = key
			index[p.ID] = len(profiles)
			profiles = append(profiles, p)
		}
		if err := rows.Err(); err != nil {
			return err
		}
		if err := rows.Close(); err != nil {
			return err
		}
		if len(profiles) == 0 {
			return nil
		}
		// 全部档案的模型只查询一次，保留原 kind/sort_order/id 顺序，避免 N+1。
		models, err := tx.QueryContext(ctx, `SELECT id,profile_id::text,kind,model_name,sort_order,context_window,created_at FROM ai_model_profile_model ORDER BY kind,sort_order,id`)
		if err != nil {
			return err
		}
		defer models.Close()
		for models.Next() {
			var m Model
			if err := models.Scan(&m.ID, &m.ProfileID, &m.Kind, &m.Name, &m.SortOrder, &m.ContextWindow, &m.CreatedAt); err != nil {
				return err
			}
			if i, found := index[m.ProfileID]; found {
				profiles[i].Models = append(profiles[i].Models, m)
			}
		}
		return models.Err()
	})
	if err != nil {
		return nil, err
	}
	return profiles, nil
}
