package model

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

const profileColumns = `id::text,name,preset_id,base_url,api_key,chat_model,temperature,embedding_model,audio_model,protocol,active,sort_order,revision,created_at,updated_at`

type queryer interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
}

func (s *PostgresReadStore) require(ctx context.Context, q queryer, id string) (Profile, error) {
	p := Profile{Models: []Model{}}
	err := q.QueryRowContext(ctx, `SELECT `+profileColumns+` FROM ai_model_profile WHERE id=$1`, id).Scan(&p.ID, &p.Name, &p.PresetID, &p.BaseURL, &p.APIKey, &p.ChatModel, &p.Temperature, &p.EmbeddingModel, &p.AudioModel, &p.Protocol, &p.Active, &p.SortOrder, &p.Revision, &p.CreatedAt, &p.UpdatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return Profile{}, missing(id)
	}
	if err != nil {
		return Profile{}, err
	}
	p.APIKey, err = s.keys.Decrypt(p.APIKey)
	if err != nil {
		return Profile{}, err
	}
	rows, err := q.QueryContext(ctx, `SELECT id,kind,model_name,sort_order,context_window,created_at FROM ai_model_profile_model WHERE profile_id=$1 ORDER BY kind,sort_order,id`, id)
	if err != nil {
		return Profile{}, err
	}
	defer rows.Close()
	for rows.Next() {
		m := Model{ProfileID: id}
		if err = rows.Scan(&m.ID, &m.Kind, &m.Name, &m.SortOrder, &m.ContextWindow, &m.CreatedAt); err != nil {
			return Profile{}, err
		}
		p.Models = append(p.Models, m)
	}
	return p, rows.Err()
}
func (s *PostgresReadStore) Require(ctx context.Context, id string) (p Profile, err error) {
	err = s.pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error { p, err = s.require(ctx, tx, id); return err })
	return
}
func (s *PostgresReadStore) Save(ctx context.Context, c SaveCommand) (v Values, err error) {
	err = s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, `LOCK TABLE ai_model_config IN SHARE ROW EXCLUSIVE MODE`); e != nil {
			return e
		}
		var old *string
		e := tx.QueryRowContext(ctx, `SELECT api_key FROM ai_model_config WHERE id=1`).Scan(&old)
		if e != nil && !errors.Is(e, sql.ErrNoRows) {
			return e
		}
		if c.APIKey == nil {
			old, e = s.keys.Decrypt(old)
			if e != nil {
				return e
			}
		} else {
			old = blankToNull(c.APIKey)
		}
		key, e := s.keys.Encrypt(old)
		if e != nil {
			return e
		}
		_, e = tx.ExecContext(ctx, `INSERT INTO ai_model_config(id,base_url,api_key,chat_model,temperature,embedding_model,revision,updated_at) VALUES(1,$1,$2,$3,$4,$5,1,$6) ON CONFLICT(id) DO UPDATE SET base_url=$1,api_key=$2,chat_model=$3,temperature=$4,embedding_model=$5,revision=ai_model_config.revision+1,updated_at=$6`, blankToNull(c.BaseURL), key, blankToNull(c.ChatModel), c.Temperature, blankToNull(c.EmbeddingModel), time.Now().UTC())
		if e != nil {
			return e
		}
		return s.scanValues(tx.QueryRowContext(ctx, `SELECT base_url,api_key,chat_model,temperature,embedding_model,NULL::text,revision,updated_at FROM ai_model_config WHERE id=1`), &v)
	})
	return
}

// 写事务串行化档案集合，保护「最后一套」、排序与唯一 active 的跨行不变量。
func (s *PostgresReadStore) write(ctx context.Context, f func(*sql.Tx) error) error {
	return s.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `LOCK TABLE ai_model_profile IN SHARE ROW EXCLUSIVE MODE`); err != nil {
			return err
		}
		return f(tx)
	})
}
func (s *PostgresReadStore) replace(ctx context.Context, tx *sql.Tx, id string, c ProfileCommand, now time.Time) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM ai_model_profile_model WHERE profile_id=$1`, id); err != nil {
		return err
	}
	for i, list := range [][]*string{c.ChatModels, c.EmbeddingModels, c.AudioModels} {
		seen := map[string]bool{}
		for _, p := range list {
			name := blankToNull(p)
			if name == nil || seen[*name] {
				continue
			}
			order := len(seen)
			seen[*name] = true
			window := LookupKnownWindow(*name)
			if i == 0 {
				if w := c.ContextWindows[*name]; w != nil && *w >= 1024 {
					window = copyPointer(w)
				}
			}
			// 原 Java 对三种 kind 均补内置表；只有 CHAT 读出到窗口映射。
			if _, err := tx.ExecContext(ctx, `INSERT INTO ai_model_profile_model(profile_id,kind,model_name,sort_order,context_window,created_at) VALUES($1,$2,$3,$4,$5,$6)`, id, []Kind{Chat, Embedding, Audio}[i], *name, order, window, now); err != nil {
				return err
			}
		}
	}
	return nil
}
func (s *PostgresReadStore) Create(ctx context.Context, c ProfileCommand) (p Profile, err error) {
	err = s.write(ctx, func(tx *sql.Tx) error {
		key, e := s.keys.Encrypt(blankToNull(c.APIKey))
		if e != nil {
			return e
		}
		now := time.Now().UTC()
		var id string
		e = tx.QueryRowContext(ctx, `INSERT INTO ai_model_profile(name,preset_id,base_url,api_key,chat_model,temperature,embedding_model,audio_model,protocol,active,sort_order,revision,created_at,updated_at) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,false,(SELECT COALESCE(MAX(sort_order)+1,0) FROM ai_model_profile),1,$10,$10) RETURNING id::text`, strip(*c.Name), blankToNull(c.PresetID), blankToNull(c.BaseURL), key, firstModel(c.ChatModels, c.ChatModel), c.Temperature, firstModel(c.EmbeddingModels, c.EmbeddingModel), firstModel(c.AudioModels, nil), ParseProtocol(value(c.Protocol)), now).Scan(&id)
		if e != nil {
			return e
		}
		if e = s.replace(ctx, tx, id, c, now); e != nil {
			return e
		}
		p, e = s.require(ctx, tx, id)
		return e
	})
	return
}
func (s *PostgresReadStore) Update(ctx context.Context, id string, c ProfileCommand) (p Profile, err error) {
	err = s.write(ctx, func(tx *sql.Tx) error {
		previous, e := s.require(ctx, tx, id)
		if e != nil {
			return e
		}
		key := previous.APIKey
		if c.APIKey != nil {
			key = blankToNull(c.APIKey)
		}
		key, e = s.keys.Encrypt(key)
		if e != nil {
			return e
		}
		now := time.Now().UTC()
		_, e = tx.ExecContext(ctx, `UPDATE ai_model_profile SET name=$2,preset_id=$3,base_url=$4,api_key=$5,chat_model=$6,temperature=$7,embedding_model=$8,audio_model=$9,protocol=$10,revision=revision+1,updated_at=$11 WHERE id=$1`, id, strip(*c.Name), blankToNull(c.PresetID), blankToNull(c.BaseURL), key, firstModel(c.ChatModels, c.ChatModel), c.Temperature, firstModel(c.EmbeddingModels, c.EmbeddingModel), firstModel(c.AudioModels, nil), ParseProtocol(value(c.Protocol)), now)
		if e != nil {
			return e
		}
		if e = s.replace(ctx, tx, id, c, now); e != nil {
			return e
		}
		p, e = s.require(ctx, tx, id)
		return e
	})
	return
}
func (s *PostgresReadStore) Activate(ctx context.Context, id string) (p Profile, err error) {
	err = s.write(ctx, func(tx *sql.Tx) error {
		var e error
		p, e = s.require(ctx, tx, id)
		if e != nil || p.Active {
			return e
		}
		now := time.Now().UTC()
		if _, e = tx.ExecContext(ctx, `UPDATE ai_model_profile SET active=false,updated_at=$1 WHERE active`, now); e != nil {
			return e
		}
		if _, e = tx.ExecContext(ctx, `UPDATE ai_model_profile SET active=true,updated_at=$2 WHERE id=$1`, id, now); e != nil {
			return e
		}
		p, e = s.require(ctx, tx, id)
		return e
	})
	return
}
func (s *PostgresReadStore) Delete(ctx context.Context, id string) error {
	return s.write(ctx, func(tx *sql.Tx) error {
		p, e := s.require(ctx, tx, id)
		if e != nil {
			return e
		}
		if p.Active {
			return api.NewBusinessError(api.BusinessConflict, "不能删除当前生效的配置，请先切换到其他配置")
		}
		var count int
		if e = tx.QueryRowContext(ctx, `SELECT COUNT(*) FROM ai_model_profile`).Scan(&count); e != nil {
			return e
		}
		if count <= 1 {
			return api.NewBusinessError(api.BusinessConflict, "至少需要保留一套配置，无法删除最后一套")
		}
		_, e = tx.ExecContext(ctx, `DELETE FROM ai_model_profile WHERE id=$1`, id)
		return e
	})
}
func (s *PostgresReadStore) Reorder(ctx context.Context, ids []string) error {
	if len(ids) == 0 {
		return ctx.Err()
	}
	return s.write(ctx, func(tx *sql.Tx) error {
		rows, e := tx.QueryContext(ctx, `SELECT id::text FROM ai_model_profile ORDER BY sort_order,created_at`)
		if e != nil {
			return e
		}
		all := []string{}
		for rows.Next() {
			var id string
			if e = rows.Scan(&id); e != nil {
				rows.Close()
				return e
			}
			all = append(all, id)
		}
		e = rows.Err()
		rows.Close()
		if e != nil {
			return e
		}
		exists := map[string]bool{}
		for _, id := range all {
			exists[id] = true
		}
		order := 0
		for _, id := range append(append([]string{}, ids...), all...) {
			if !exists[id] {
				continue
			}
			delete(exists, id)
			if _, e = tx.ExecContext(ctx, `UPDATE ai_model_profile SET sort_order=$2 WHERE id=$1`, id, order); e != nil {
				return e
			}
			order++
		}
		return nil
	})
}
func (s *PostgresReadStore) Rename(ctx context.Context, id, name string) (p Profile, err error) {
	if strip(name) == "" {
		return p, validation("配置名称不能为空")
	}
	if err = checkLength(&name, 60, "配置名称"); err != nil {
		return
	}
	err = s.write(ctx, func(tx *sql.Tx) error {
		if _, e := s.require(ctx, tx, id); e != nil {
			return e
		}
		_, e := tx.ExecContext(ctx, `UPDATE ai_model_profile SET name=$2,updated_at=$3 WHERE id=$1`, id, strip(name), time.Now().UTC())
		if e != nil {
			return e
		}
		p, e = s.require(ctx, tx, id)
		return e
	})
	return
}
func value(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}
