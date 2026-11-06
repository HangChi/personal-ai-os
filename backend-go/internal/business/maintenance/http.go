package maintenance

import (
	"errors"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"strings"
)

const basePath = "/api/v1/maintenance"

func OwnsPath(path string) bool { return path == basePath || strings.HasPrefix(path, basePath+"/") }
func (s *Service) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		var result any
		var e error
		switch r.URL.Path {
		case basePath:
			if r.Method != "GET" && r.Method != "HEAD" {
				return web.MethodNotSupported(r.Method, []string{"GET"})
			}
			result, e = s.Snapshot(r.Context())
		case basePath + "/verify", basePath + "/drill-reports":
			if r.Method != "POST" {
				return web.MethodNotSupported(r.Method, []string{"POST"})
			}
			mediaType, _, parseErr := mime.ParseMediaType(r.Header.Get("Content-Type"))
			if parseErr != nil || mediaType != "application/json" {
				return errors.New("请求媒体类型不受支持")
			}
			external := strings.HasSuffix(r.URL.Path, "/drill-reports")
			limit := MaxBytes
			if external {
				limit = 16384
			}
			raw, readErr := io.ReadAll(io.LimitReader(r.Body, int64(limit+1)))
			if readErr != nil {
				return invalid("读取文件失败")
			}
			if external {
				result, e = s.Drill(r.Context(), raw)
			} else {
				result, e = s.Verify(r.Context(), raw)
			}
		default:
			return web.NotFound()
		}
		if e != nil {
			return e
		}
		if r.Method == "HEAD" {
			w.Header().Set("Content-Type", media)
			return nil
		}
		return api.WriteControllerMediaJSON(w, 200, api.OK(result), media)
	}, logger)
}
