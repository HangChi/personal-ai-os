// Package maintenance 仅维护运行状态与纯校验记录，不持有业务导入或模型能力。
package maintenance

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	"sync"
	"time"
	"unicode/utf8"
)

const MaxBytes = 20 << 20

type Service struct {
	Pool       *database.Pool
	instanceID string
	role       config.Role
	slots      chan struct{}
}

func NewService(pool *database.Pool, role config.Role) (*Service, error) {
	var id [16]byte
	if _, e := rand.Read(id[:]); e != nil {
		return nil, errors.New("无法创建心跳实例标识")
	}
	id[6] = (id[6] & 0x0f) | 0x40
	id[8] = (id[8] & 0x3f) | 0x80
	return &Service{Pool: pool, role: role, instanceID: fmt.Sprintf("%x-%x-%x-%x-%x", id[:4], id[4:6], id[6:8], id[8:10], id[10:]), slots: make(chan struct{}, 2)}, nil
}
func invalid(message string) error { return api.NewBusinessError(api.ValidationFailed, message) }
func (s *Service) Snapshot(ctx context.Context) (map[string]any, error) {
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	var raw string
	e := s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		return c.QueryRowContext(ctx, "SELECT maintenance_snapshot()::text").Scan(&raw)
	})
	if e != nil {
		return nil, e
	}
	return decode(raw)
}
func decode(raw string) (map[string]any, error) {
	var v map[string]any
	e := json.Unmarshal([]byte(raw), &v)
	return v, e
}
func (s *Service) Verify(ctx context.Context, raw []byte) (map[string]any, error) {
	return s.check(ctx, raw, false)
}
func (s *Service) Drill(ctx context.Context, raw []byte) (map[string]any, error) {
	return s.check(ctx, raw, true)
}
func (s *Service) check(ctx context.Context, raw []byte, external bool) (result map[string]any, err error) {
	limit := MaxBytes
	if external {
		limit = 16384
	}
	if len(raw) > limit {
		return nil, invalid("文件超过校验大小上限")
	}
	select {
	case s.slots <- struct{}{}:
		defer func() { <-s.slots }()
	default:
		return nil, api.NewBusinessError(api.BusinessConflict, "正在处理其他校验，请稍后重试")
	}
	content := string(raw)
	if !utf8.Valid(raw) || bytes.ContainsRune(raw, 0) {
		if external {
			return nil, invalid("报告需为有效UTF-8 JSON")
		}
		content = ""
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	digest := sha256.Sum256(raw)
	err = s.Pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, e := tx.ExecContext(ctx, "SET LOCAL statement_timeout='10s'"); e != nil {
			return e
		}
		query := "SELECT maintenance_verify_text($1)::text"
		kind := "BUSINESS_JSON"
		if external {
			query = "SELECT maintenance_drill_text($1)::text"
			kind = "EXTERNAL_DRILL"
		}
		var rawResult string
		if e := tx.QueryRowContext(ctx, query, content).Scan(&rawResult); e != nil {
			return e
		}
		if external {
			v, e := decode(rawResult)
			if e != nil {
				return e
			}
			if v["status"] != "UNVERIFIED" {
				return invalid("外部报告需填写工具、摘要、完成时间及PASSED/FAILED结果")
			}
		}
		var stored string
		if e := tx.QueryRowContext(ctx, "SELECT maintenance_save($1,$2,$3::jsonb)::text", kind, hex.EncodeToString(digest[:]), rawResult).Scan(&stored); e != nil {
			return e
		}
		var e error
		result, e = decode(stored)
		return e
	})
	return
}
func (s *Service) Heartbeat(ctx context.Context) error {
	return s.Pool.WithConn(ctx, func(c *sql.Conn) error {
		if _, e := c.ExecContext(ctx, "INSERT INTO runtime_heartbeat(instance_id,backend,role) VALUES($1,'GO',$2) ON CONFLICT(instance_id) DO UPDATE SET last_seen_at=clock_timestamp()", s.instanceID, string(s.role)); e != nil {
			return e
		}
		_, e := c.ExecContext(ctx, "DELETE FROM runtime_heartbeat WHERE last_seen_at<now()-interval '7 days'")
		return e
	})
}
func (s *Service) Start(parent context.Context, logger *slog.Logger) func() {
	return s.start(parent, logger, 30*time.Second)
}
func (s *Service) start(parent context.Context, logger *slog.Logger, interval time.Duration) func() {
	ctx, cancel := context.WithCancel(parent)
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		tick := time.NewTicker(interval)
		defer tick.Stop()
		for {
			work, stop := context.WithTimeout(ctx, 5*time.Second)
			e := s.Heartbeat(work)
			stop()
			if e != nil && ctx.Err() == nil {
				logger.Warn("维护心跳本轮未完成")
			}
			select {
			case <-ctx.Done():
				return
			case <-tick.C:
			}
		}
	}()
	return func() { cancel(); wg.Wait() }
}
