//go:build integration

package app

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	httpmiddleware "personal-ai-os/backend-go/internal/platform/http"
)

func TestFoundationRequestIDReachesDetachedExecutor(t *testing.T) {
	type privateKey struct{}
	var f *Foundation
	started := make(chan struct{})
	release := make(chan struct{})
	result := make(chan string, 1)
	f = fixtureFoundation(t, map[string]string{"app.rate-limit.enabled": "false"}, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !f.Executor().Submit(r.Context(), func(ctx context.Context) error {
			close(started)
			<-release
			if ctx.Err() != nil || ctx.Value(privateKey{}) != nil {
				result <- "错误继承HTTP生命周期或私有值"
			} else {
				result <- httpmiddleware.RequestID(ctx)
			}
			return nil
		}) {
			t.Error("提交失败")
		}
		w.WriteHeader(204)
	}))
	source, cancel := context.WithCancel(context.WithValue(context.Background(), privateKey{}, "private"))
	defer cancel()
	r := httptest.NewRequest("GET", "/submit", nil).WithContext(source)
	r.Header.Set(httpmiddleware.RequestIDHeader, "foundation-detached-42")
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, r)
	select {
	case <-started:
	case <-time.After(time.Second):
		t.Fatal("任务未开始")
	}
	cancel()
	close(release)
	select {
	case id := <-result:
		if id != "foundation-detached-42" {
			t.Fatal(id)
		}
	case <-time.After(time.Second):
		t.Fatal("后台任务未完成")
	}
	if w.Code != 204 {
		t.Fatal("HTTP分派失败")
	}
}
