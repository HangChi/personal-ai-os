// Package health 报告实际依赖状态；不以模型未配置拖垮整体健康。
package health

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"time"

	"personal-ai-os/backend-go/internal/platform/database"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

type Options struct {
	Application  string
	Version      string
	ProbeTimeout time.Duration
}

// Probe 必须遵守 context；不通过遗留 goroutine 伪造超时返回。
type Probe func(context.Context) error

type Status struct {
	Status      string            `json:"status"`
	Application string            `json:"application"`
	Version     string            `json:"version"`
	Timestamp   time.Time         `json:"timestamp"`
	Components  map[string]string `json:"components"`
}

type Service struct {
	options         Options
	probe           Probe
	modelConfigured func() bool
	logger          *slog.Logger
}

func New(options Options, probe Probe, modelConfigured func() bool, logger *slog.Logger) (*Service, error) {
	if probe == nil || modelConfigured == nil || logger == nil {
		return nil, errors.New("健康探测依赖不能为空")
	}
	if options.ProbeTimeout <= 0 {
		return nil, errors.New("健康探测期限必须为正时长")
	}
	return &Service{options: options, probe: probe, modelConfigured: modelConfigured, logger: logger}, nil
}

// CurrentStatus 每次执行廉价数据库探测；模型仅查本地配置，不调用模型协议。
// 数据库错误和取消报告 DOWN，HTTP 层仍返回 200；不是 readiness 发布门禁。
func (service *Service) CurrentStatus(ctx context.Context) Status {
	probeCtx, cancel := context.WithTimeout(ctx, service.options.ProbeTimeout)
	err := service.probe(probeCtx)
	cancel()
	state := "UP"
	if err != nil {
		state = "DOWN"
		// 原 Java 日志会包含驱动消息；Go 明确收紧为仅记录脱敏提示/请求标识。
		service.logger.Warn("健康检查：数据库连接失败", "requestId", httpmiddleware.RequestID(ctx))
	}
	ai := "NOT_CONFIGURED"
	if service.modelConfigured() {
		ai = "CONFIGURED"
	}
	return Status{Status: state, Application: service.options.Application, Version: service.options.Version,
		Timestamp: time.Now().UTC(), Components: map[string]string{"database": state, "ai": ai}}
}

// NewPostgresProbe 使用调用方已有 Pool 的 SELECT 1；不创建连接池、不读业务表或执行 DDL。
func NewPostgresProbe(pool *database.Pool) (Probe, error) {
	if pool == nil {
		return nil, errors.New("健康数据库连接池不能为空")
	}
	return func(ctx context.Context) error {
		return pool.WithConn(ctx, func(conn *sql.Conn) error {
			var value int
			if err := conn.QueryRowContext(ctx, "SELECT 1").Scan(&value); err != nil {
				return err
			}
			if value != 1 {
				return errors.New("健康探测返回无效结果")
			}
			return nil
		})
	}, nil
}
