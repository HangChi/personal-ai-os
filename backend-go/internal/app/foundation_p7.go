package app

import (
	"log/slog"
	"net/http"
	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/chat"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/business/exchange"
	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/mcp"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/search"
	"personal-ai-os/backend-go/internal/business/task"
)

func (f *Foundation) p7Route(tasks *task.Service, p5 *foundationP5, personal mcp.PersonalOptions, name, version string, logger *slog.Logger, previous http.Handler) http.Handler {
	protocol := mcp.NewPersonalServer(personal, mcp.PersonalServices{Tasks: tasks, Knowledge: f.p6.Retriever, Calendar: p5.events, Memories: p5.store})
	status := &mcp.HTTP{Registry: f.mcp, Personal: personal, Protocol: protocol}
	mcpHandler := status.Handler(logger)
	searches := (&search.Service{Contributors: search.Contributors(f.pool, tasks, f.p6.Store), Logger: logger}).Handler(logger)
	exports := &dataexport.Service{Pool: f.pool, Name: name, Version: version, Contributors: []dataexport.Contributor{task.ExportContributor{}, chat.ExportContributor{}, calendar.ExportContributor{}, memory.ExportContributor{}, knowledge.ExportContributor{Store: f.p6.Store}}}
	transfers := (&exchange.HTTP{Export: exports, Import: &dataimport.Service{Pool: f.pool, Knowledge: f.p6.Store}}).Handler(logger)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case status.OwnsPath(r.URL.Path):
			mcpHandler.ServeHTTP(w, r)
		case r.URL.Path == "/api/v1/search":
			searches.ServeHTTP(w, r)
		case r.URL.Path == "/api/v1/export" || r.URL.Path == "/api/v1/import":
			transfers.ServeHTTP(w, r)
		default:
			previous.ServeHTTP(w, r)
		}
	})
}
