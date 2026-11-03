package httpmiddleware

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"testing"
)

func TestRequestIDValidationAndJavaTrim(t *testing.T) {
	generated := regexp.MustCompile(`^[a-f0-9]{12}$`)
	for _, test := range []struct {
		input, want string
	}{
		{"request-1.A_b", "request-1.A_b"},
		{" \t request-2 \n", "request-2"},
		{strings.Repeat("a", 64), strings.Repeat("a", 64)},
		{"", ""}, {strings.Repeat("a", 65), ""}, {"bad\nid", ""}, {"中文", ""},
		{"\u00a0safe\u00a0", ""},
	} {
		got := resolveRequestID(test.input)
		if (test.want != "" && got != test.want) || (test.want == "" && !generated.MatchString(got)) {
			t.Errorf("requestId(%q)=%q，期望 %q 或 12 位随机 hex", test.input, got, test.want)
		}
	}
}

func TestConcurrentRequestIDsDoNotLeak(t *testing.T) {
	handler := RequestIDMiddleware(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		if RequestID(request.Context()) != request.Header.Get(RequestIDHeader) {
			t.Error("context 中的 requestId 被其他请求污染")
		}
		w.WriteHeader(204)
	}))
	var group sync.WaitGroup
	for index := 0; index < 32; index++ {
		group.Add(1)
		go func(index int) {
			defer group.Done()
			id := fmt.Sprintf("request-%d", index)
			request := httptest.NewRequest(http.MethodGet, "/", nil)
			request.Header.Set(RequestIDHeader, id)
			writer := httptest.NewRecorder()
			handler.ServeHTTP(writer, request)
			if writer.Header().Get(RequestIDHeader) != id {
				t.Error("响应 requestId 被其他请求污染")
			}
		}(index)
	}
	group.Wait()
}
