package exchange

import (
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"time"
)

type HTTP struct {
	Export *dataexport.Service
	Import *dataimport.Service
}

func (h *HTTP) Handler(logger *slog.Logger) http.Handler { return web.HandleErrors(h.serve, logger) }
func (h *HTTP) serve(w http.ResponseWriter, r *http.Request) error {
	switch r.URL.Path {
	case "/api/v1/export":
		if r.Method != "GET" && r.Method != "HEAD" {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json"})
		if !ok {
			return web.NotAcceptable()
		}
		w.Header().Set("Content-Type", media)
		w.Header().Set("Content-Disposition", `attachment; filename="personal-ai-os-export-`+time.Now().UTC().Format("20060102-150405")+`.json"`)
		if r.Method == "HEAD" {
			return nil
		}
		return h.Export.Write(r.Context(), w)
	case "/api/v1/import":
		if r.Method != "POST" {
			return web.MethodNotSupported(r.Method, []string{"POST"})
		}
		media, _, e := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if e != nil || media != "application/json" {
			return errors.New("请求媒体类型不受支持")
		}
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		if r.ContentLength > dataimport.MaxBytes {
			return api.NewBusinessError(api.ValidationFailed, "文件过大，上限 20MB")
		}
		raw, e := io.ReadAll(io.LimitReader(r.Body, dataimport.MaxBytes+1))
		if e != nil {
			return web.InvalidBody(e)
		}
		result, e := h.Import.Import(r.Context(), raw)
		if e != nil {
			return e
		}
		return api.WriteControllerMediaJSON(w, 200, api.OK(result), media)
	default:
		return web.NotFound()
	}
}
