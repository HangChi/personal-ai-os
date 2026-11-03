package app

import (
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestFoundationMultipartRealServletOracle(t *testing.T) {
	fixtures := readFoundationHTTPFixture(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	directory := t.TempDir()
	// 测试适配器只展示解析结果；两个fixture路由不进入生产代码，不冒充上传业务。
	business := httpmiddleware.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		if !strings.HasPrefix(r.URL.Path, "/api/v1/fixture-") {
			return httpmiddleware.NotFound()
		}
		upload, err := httpmiddleware.ParseMultipart(w, r, httpmiddleware.MultipartLimits{MaxFileBytes: 64, MaxRequestBytes: 1024, TempDirectory: directory})
		if err != nil {
			return err
		}
		defer upload.Close()
		files := upload.Files["file"]
		if len(files) == 0 {
			return httpmiddleware.MissingParameter("file")
		}
		file := files[0]
		reader, err := file.Open()
		if err != nil {
			return err
		}
		defer reader.Close()
		data, err := io.ReadAll(reader)
		if err != nil {
			return err
		}
		return api.WriteControllerJSON(w, 200, api.OK(map[string]any{"filename": file.Filename, "contentType": file.ContentType, "size": file.Size, "empty": file.Size == 0, "bytes": base64.StdEncoding.EncodeToString(data)}))
	}, logger)
	for _, mode := range []struct{ auth, csrf, monitoring bool }{{false, false, false}, {true, false, false}, {true, true, false}, {true, true, true}} {
		server := httptest.NewServer(fixtureFoundationHandler(t, config.API, mode.auth, mode.csrf, &fixtureLimiter{allowed: true}, nil, business))
		transport := &http.Transport{Proxy: nil}
		for _, test := range fixtures {
			if test.Auth != mode.auth || test.CSRF != mode.csrf || test.Monitoring != mode.monitoring || test.Request.Management || !strings.HasPrefix(test.Request.Path, "/api/v1/fixture-") {
				continue
			}
			t.Run(strings.Join([]string{map[bool]string{true: "auth", false: "anonymous"}[mode.auth], map[bool]string{true: "csrf", false: "plain"}[mode.csrf], map[bool]string{true: "monitoring", false: "business"}[mode.monitoring], test.Request.Name}, "/"), func(t *testing.T) {
				jar, _ := cookiejar.New(nil)
				client := &http.Client{Transport: transport, Jar: jar, Timeout: 5 * time.Second}
				compareFoundationResponse(t, client, server.URL, test)
				entries, err := os.ReadDir(directory)
				if err != nil || len(entries) != 0 {
					t.Fatal("上传成功/失败后未清理本次暂存", err)
				}
			})
		}
		transport.CloseIdleConnections()
		server.Close()
	}
}
