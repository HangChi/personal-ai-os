package httpmiddleware

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/modelgate"
)

func TestConcurrencyHoldsStreamUntilHandlerCompletion(t *testing.T) {
	gate, _ := modelgate.NewLocal(1)
	logger := slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil))
	started, finish, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
	handler := ConcurrencyMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("data: started\n\n"))
		w.(http.Flusher).Flush()
		close(started)
		<-finish
		_, _ = w.Write([]byte("data: done\n\n"))
	}), gate, logger)
	first := httptest.NewRecorder()
	go func() {
		defer close(done)
		handler.ServeHTTP(first, httptest.NewRequest("POST", "/api/v1/knowledge/ask", nil))
	}()
	select {
	case <-started:
	case <-time.After(3 * time.Second):
		t.Fatal("未开始流")
	}
	blocked := httptest.NewRecorder()
	handler.ServeHTTP(blocked, httptest.NewRequest("POST", "/api/v1/chat/%75ploads", nil))
	if blocked.Code != 429 || !strings.Contains(blocked.Body.String(), `"code":42900`) {
		t.Fatal("流存续期间必须拒绝新的模型请求")
	}
	close(finish)
	<-done
	if first.Code != 200 || first.Body.String() != "data: started\n\ndata: done\n\n" {
		t.Fatal("中间件不能缓冲或改写 SSE")
	}
	lease, err := gate.TryAcquire(context.Background())
	if lease == nil || err != nil {
		t.Fatal("结束后必须归还许可")
	}
	_ = lease.Release(context.Background())
}

func TestConcurrencyCleanupOnPanicCancellationAndNormalRoutes(t *testing.T) {
	gate, _ := modelgate.NewLocal(1)
	logger := slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil))
	for _, mode := range []string{"success", "panic", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			handler := ConcurrencyMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if mode == "panic" {
					panic("fixture")
				}
				if mode == "cancel" {
					cancel()
				}
				w.WriteHeader(204)
			}), gate, logger)
			func() {
				defer func() {
					p := recover()
					if mode == "panic" {
						if p != "fixture" {
							t.Fatal("panic 必须传播")
						}
					} else if p != nil {
						panic(p)
					}
				}()
				handler.ServeHTTP(httptest.NewRecorder(), httptest.NewRequest("POST", "/api/v1/knowledge/search", nil).WithContext(ctx))
			}()
			lease, err := gate.TryAcquire(context.Background())
			if lease == nil || err != nil {
				t.Fatal("失败/取消/同步完成必须归还许可")
			}
			_ = lease.Release(context.Background())
		})
	}
	lease, _ := gate.TryAcquire(context.Background())
	defer lease.Release(context.Background())
	called := false
	handler := ConcurrencyMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true; w.WriteHeader(204) }), gate, logger)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, httptest.NewRequest("POST", "/api/v1/tasks", nil))
	if !called || response.Code != 204 {
		t.Fatal("本地查询/普通路由不得受模型并发限制")
	}
}

func TestConcurrencyRealHTTPStreamDisconnectReleasesPermit(t *testing.T) {
	gate, _ := modelgate.NewLocal(1)
	logger := slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil))
	done := make(chan struct{})
	guard := ConcurrencyMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Test-Stream") == "hold" {
			w.Header().Set("Content-Type", "text/event-stream")
			_, _ = w.Write([]byte("data: ready\n\n"))
			w.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}), gate, logger)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		guard.ServeHTTP(w, r)
		if r.Header.Get("X-Test-Stream") == "hold" {
			close(done)
		}
	}))
	ctx, cancel := context.WithCancel(context.Background())
	defer func() { cancel(); server.Close() }()
	client := server.Client()
	client.Timeout = 3 * time.Second
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, server.URL+"/api/v1/knowledge/ask", nil)
	request.Header.Set("X-Test-Stream", "hold")
	stream, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	defer stream.Body.Close()
	if stream.StatusCode != 200 || stream.Header.Get("Content-Type") != "text/event-stream" {
		t.Fatal("真实 SSE 未开始")
	}
	blocked, err := client.Post(server.URL+"/api/v1/knowledge/ask", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	blocked.Body.Close()
	if blocked.StatusCode != 429 {
		t.Fatal("真实流存续时必须拒绝第二请求")
	}
	stream.Body.Close()
	cancel()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("断开未触发处理器退出和释放")
	}
	next, err := client.Post(server.URL+"/api/v1/knowledge/ask", "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	next.Body.Close()
	if next.StatusCode != 204 {
		t.Fatal("断开后下一请求应取得许可")
	}
}
