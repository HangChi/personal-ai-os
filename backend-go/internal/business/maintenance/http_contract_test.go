package maintenance

import (
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/config"
)

type brokenMaintenanceBody struct{}

func (brokenMaintenanceBody) Read([]byte) (int, error) {
	return 0, errors.New("synthetic read failure")
}
func (brokenMaintenanceBody) Close() error { return nil }

func TestMaintenanceHTTPSnapshotVerifyAndHeadContracts(t *testing.T) {
	for _, method := range []string{"GET", "HEAD", "POST"} {
		t.Run(method, func(t *testing.T) {
			steps := []sqlStep{queryStep("maintenance_snapshot", sqlValues("{}"))}
			path := basePath
			if method == "POST" {
				path += "/verify"
				steps = []sqlStep{terminalStep("begin"), execStep("SET LOCAL"), queryStep("maintenance_verify_text", sqlValues(`{"status":"UNVERIFIED"}`)), queryStep("maintenance_save", sqlValues(`{"status":"UNVERIFIED"}`)), terminalStep("commit")}
			}
			service, err := NewService(contractPool(t, steps...), config.API)
			if err != nil {
				t.Fatal(err)
			}
			request := httptest.NewRequest(method, path, strings.NewReader("{}"))
			request.Header.Set("Content-Type", "application/json")
			response := httptest.NewRecorder()
			service.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(response, request)
			if response.Code != 200 {
				t.Fatal(response.Code, response.Body.String())
			}
			if method == "HEAD" && response.Body.Len() != 0 {
				t.Fatal("HEAD response leaked body")
			}
			if method != "HEAD" && !strings.Contains(response.Body.String(), `"code"`) {
				t.Fatal(response.Body.String())
			}
		})
	}
	service, _ := NewService(contractPool(t), config.API)
	for _, mode := range []string{"media", "read"} {
		request := httptest.NewRequest("POST", basePath+"/verify", nil)
		request.Header.Set("Content-Type", "text/plain")
		if mode == "read" {
			request.Header.Set("Content-Type", "application/json")
			request.Body = brokenMaintenanceBody{}
		}
		response := httptest.NewRecorder()
		service.Handler(slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(response, request)
		if response.Code == 200 {
			t.Fatal("invalid input accepted")
		}
	}
}
