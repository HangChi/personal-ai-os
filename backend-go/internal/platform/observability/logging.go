// Package observability提供实际Go日志与运行观测，不仿造JVM线程或内存指标。
package observability

import (
	"context"
	"io"
	"log/slog"
	"os"
	"strings"

	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func NewLogger(writer io.Writer, settings config.LoggingProperties) *slog.Logger {
	options := &slog.HandlerOptions{Level: settings.Level}
	var handler slog.Handler
	if settings.Format == "ecs" {
		options.ReplaceAttr = func(groups []string, a slog.Attr) slog.Attr {
			if len(groups) == 0 {
				switch a.Key {
				case slog.TimeKey:
					a.Key = "@timestamp"
				case slog.LevelKey:
					a.Key = "log.level"
					a.Value = slog.StringValue(strings.ToLower(a.Value.String()))
				case slog.MessageKey:
					a.Key = "message"
				}
			}
			return a
		}
		handler = slog.NewJSONHandler(writer, options).WithAttrs([]slog.Attr{slog.String("ecs.version", "8.11.0"), slog.String("service.name", "personal-ai-os"), slog.Int("process.pid", os.Getpid()), slog.String("service.runtime", "go")})
	} else {
		handler = slog.NewTextHandler(writer, options)
	}
	return slog.New(&requestHandler{Handler: handler})
}

type requestHandler struct {
	slog.Handler
	hasRequestID bool
}

func (h *requestHandler) Handle(ctx context.Context, r slog.Record) error {
	seen := h.hasRequestID
	copy := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	r.Attrs(func(a slog.Attr) bool {
		if a.Key == "requestId" {
			seen = true
			if a.Value.Kind() == slog.KindString && a.Value.String() == "" {
				a.Value = slog.StringValue("------------")
			}
		}
		copy.AddAttrs(a)
		return true
	})
	if !seen {
		id := httpmiddleware.RequestID(ctx)
		if id == "" {
			id = "------------"
		}
		copy.AddAttrs(slog.String("requestId", id))
	}
	return h.Handler.Handle(ctx, copy)
}
func (h *requestHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	attrs = append([]slog.Attr(nil), attrs...)
	seen := h.hasRequestID
	for i, a := range attrs {
		if a.Key == "requestId" {
			seen = true
			if a.Value.Kind() == slog.KindString && a.Value.String() == "" {
				attrs[i].Value = slog.StringValue("------------")
			}
		}
	}
	return &requestHandler{Handler: h.Handler.WithAttrs(attrs), hasRequestID: seen}
}
func (h *requestHandler) WithGroup(name string) slog.Handler {
	return &requestHandler{Handler: h.Handler.WithGroup(name), hasRequestID: h.hasRequestID}
}
