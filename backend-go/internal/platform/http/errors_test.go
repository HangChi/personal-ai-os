package httpmiddleware

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/api"
)

func exceptionCase(name string) error {
	if strings.HasPrefix(name, "business-") && name != "business-custom" && name != "business-empty" {
		var code int
		fmt.Sscanf(name, "business-%d", &code)
		return api.NewBusinessError(api.ErrorCode(code), api.ErrorCode(code).Message())
	}
	switch name {
	case "business-custom":
		return api.NewBusinessError(api.BusinessConflict, "任务已完成😀")
	case "business-empty":
		return api.NewBusinessError(api.BusinessConflict, "")
	case "resource-custom":
		return api.NewBusinessError(api.ResourceNotFound, "指定任务不存在")
	case "validation-true":
		return ValidationFailure([]Violation{{"title", "不能为空"}, {"tags[0]", "长度超限😀"}})
	case "validation-false", "constraint-empty":
		return ValidationFailure(nil)
	case "constraint":
		return ValidationFailure([]Violation{{"list.page", "必须大于等于0"}, {"list.size", "必须小于等于200"}})
	case "type":
		return InvalidParameter("page")
	case "not-found-resource", "not-found-handler":
		return NotFound()
	case "upload":
		return UploadTooLarge(errors.New("fixture-private-upload"))
	case "json":
		return InvalidBody(errors.New("fixture-private-body"))
	case "missing-query":
		return MissingParameter("page")
	case "missing-part":
		return MissingParameter("file")
	case "method":
		return MethodNotSupported("GET", []string{"PUT", "DELETE"})
	case "accept":
		return NotAcceptable()
	case "integrity":
		return IntegrityConflict(errors.New("fixture-private-sql"))
	default:
		return errors.New("fixture-private-error")
	}
}

func TestJavaExceptionHandler36ResponseFixtures(t *testing.T) {
	data, err := os.ReadFile("testdata/java-exceptions.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string `json:"baseline_commit"`
		Cases    []struct {
			Name        string        `json:"name"`
			Status      int           `json:"status"`
			Code        api.ErrorCode `json:"code"`
			Message     string        `json:"message"`
			ContentType string        `json:"content_type"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(fixture.Cases) != 36 {
		t.Fatal("基线或对照数量错误")
	}
	for _, test := range fixture.Cases {
		t.Run(test.Name, func(t *testing.T) {
			var logs bytes.Buffer
			handler := RequestIDMiddleware(HandleErrors(func(http.ResponseWriter, *http.Request) error {
				return fmt.Errorf("private-wrapper: %w", exceptionCase(test.Name))
			}, slog.New(slog.NewJSONHandler(&logs, nil))))
			request := httptest.NewRequest("GET", "/api/v1/settings/model/profiles/42?keyword=fixture-private-query", nil)
			request.Header.Set("Accept", "text/event-stream")
			request.Header.Set("X-Request-Id", "errors-fixture")
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, request)
			var body api.Response[any]
			if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
				t.Fatal(err)
			}
			if response.Code != test.Status || body.Code != test.Code || body.Message != test.Message || body.Data != nil || body.Timestamp.IsZero() || body.Timestamp.Location() != time.UTC {
				t.Fatalf("Java/Go异常响应差分: status=%d body=%+v expected=%+v", response.Code, body, test)
			}
			if got := strings.Split(response.Header().Get("Content-Type"), ";")[0]; got != test.ContentType {
				t.Fatal("错误响应必须覆盖SSE Accept", got)
			}
			if strings.Contains(response.Body.String()+logs.String(), "fixture-private") || strings.Contains(logs.String(), "private-wrapper") {
				t.Fatal("原始异常或查询串泄露", logs.String())
			}
			if test.Name == "method" {
				if response.Header().Get("Allow") != "PUT, DELETE" || !strings.Contains(logs.String(), "\"level\":\"WARN\"") || !strings.Contains(logs.String(), "/api/v1/settings/model/profiles/42") || !strings.Contains(logs.String(), "errors-fixture") {
					t.Fatal("405日志路径/ID/Allow不完整", logs.String())
				}
			}
		})
	}
}

func TestExceptionBoundaryHEADPanicAndDisconnect(t *testing.T) {
	var logs bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	request := httptest.NewRequest("HEAD", "/api/v1/test", nil)
	response := httptest.NewRecorder()
	HandleErrors(func(http.ResponseWriter, *http.Request) error { panic("fixture-private-panic") }, logger).ServeHTTP(response, request)
	if response.Code != 500 || response.Body.Len() != 0 || !strings.Contains(logs.String(), "ERROR") || strings.Contains(logs.String(), "fixture-private") {
		t.Fatal("HEAD/panic契约错误", response, logs.String())
	}
	logs.Reset()
	response = httptest.NewRecorder()
	HandleErrors(func(http.ResponseWriter, *http.Request) error {
		return ClientDisconnected(errors.New("fixture-private-disconnect"))
	}, logger).ServeHTTP(response, httptest.NewRequest("POST", "/stream", nil))
	if response.Body.Len() != 0 || response.Flushed || strings.Contains(logs.String(), "ERROR") || !strings.Contains(logs.String(), "DEBUG") || strings.Contains(logs.String(), "fixture-private") {
		t.Fatal("断连不能再写错误信封", logs.String())
	}
	for _, cause := range []error{context.Canceled, context.DeadlineExceeded} {
		response = httptest.NewRecorder()
		HandleErrors(func(http.ResponseWriter, *http.Request) error { return cause }, logger).ServeHTTP(response, httptest.NewRequest("GET", "/test", nil))
		if response.Code != 500 {
			t.Fatal("不能把上游超时/取消误当客户端断连")
		}
	}
}

func TestExceptionBoundaryCommittedStreamAbortsWithoutJSON(t *testing.T) {
	var logs bytes.Buffer
	for _, panics := range []bool{false, true} {
		response := httptest.NewRecorder()
		handler := HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
			w.Header().Set("Content-Type", "text/event-stream")
			io.WriteString(w, "event: delta\ndata: hello\n\n")
			if err := http.NewResponseController(w).Flush(); err != nil {
				t.Fatal(err)
			}
			if panics {
				panic("fixture-private-after-commit")
			}
			return errors.New("fixture-private-after-commit")
		}, slog.New(slog.NewJSONHandler(&logs, nil)))
		func() {
			defer func() {
				if recovered := recover(); recovered != http.ErrAbortHandler {
					t.Fatalf("已提交错误必须中止连接: %v", recovered)
				}
			}()
			handler.ServeHTTP(response, httptest.NewRequest("GET", "/stream", nil))
			t.Fatal("已提交错误没有中止")
		}()
		if response.Body.String() != "event: delta\ndata: hello\n\n" || !response.Flushed || response.Header().Get("Content-Type") != "text/event-stream" {
			t.Fatal("错误污染了SSE")
		}
	}
	if strings.Contains(logs.String(), "fixture-private") {
		t.Fatal("错误详情泄露")
	}
}

func TestExceptionBoundaryRealHTTPAbortAndConcurrency(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := httptest.NewServer(HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		if r.URL.Path == "/stream" {
			io.WriteString(w, "partial")
			http.NewResponseController(w).Flush()
			return errors.New("fixture-private")
		}
		return MissingParameter("file")
	}, logger))
	defer server.Close()
	response, err := server.Client().Get(server.URL + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	body, err := io.ReadAll(response.Body)
	response.Body.Close()
	if string(body) != "partial" || !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatal("真实HTTP必须结束不完整响应", string(body), err)
	}
	var wait sync.WaitGroup
	for i := 0; i < 50; i++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			r, e := server.Client().Get(server.URL + "/normal")
			if e != nil {
				t.Error(e)
				return
			}
			defer r.Body.Close()
			b, e := io.ReadAll(r.Body)
			if e != nil || r.StatusCode != 400 || !bytes.Contains(b, []byte("缺少参数 file")) {
				t.Error("错误边界串请求", e)
			}
		}()
	}
	wait.Wait()
}

func TestRequestErrorCausesAndInputCopies(t *testing.T) {
	cause := errors.New("fixture-private")
	for _, problem := range []error{InvalidBody(cause), UploadTooLarge(cause), IntegrityConflict(cause), ClientDisconnected(cause)} {
		if !errors.Is(problem, cause) || strings.Contains(problem.Error(), "fixture-private") {
			t.Fatal("原因应可Is但不可显示", problem)
		}
	}
	violations := []Violation{{"title", "不能为空"}}
	problem := ValidationFailure(violations)
	violations[0].Message = "changed"
	response := httptest.NewRecorder()
	HandleErrors(func(http.ResponseWriter, *http.Request) error { return problem }, slog.New(slog.NewTextHandler(io.Discard, nil))).ServeHTTP(response, httptest.NewRequest("GET", "/test", nil))
	if strings.Contains(response.Body.String(), "changed") {
		t.Fatal("调用方修改了异常内容")
	}
}

func TestExceptionBoundaryClassificationPrecedenceAndHeaders(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(io.Discard, nil))
	var typedNil *api.BusinessError
	for _, test := range []struct {
		err     error
		status  int
		message string
	}{
		{InvalidBody(api.NewBusinessError(api.BusinessConflict, "must-not-override")), 400, "请求体格式错误"},
		{errors.Join(MissingParameter("private"), NotFound()), 500, "服务器内部错误"},
		{typedNil, 500, "服务器内部错误"},
	} {
		response := httptest.NewRecorder()
		HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
			w.Header().Set("Content-Length", "9999")
			w.Header().Set("Content-Encoding", "gzip")
			w.Header().Set("Content-Disposition", "attachment; filename=private")
			w.Header().Set("ETag", "old-entity")
			return test.err
		}, logger).ServeHTTP(response, httptest.NewRequest("GET", "/test", nil))
		if response.Code != test.status || !strings.Contains(response.Body.String(), test.message) {
			t.Fatal("分类顺序不确定", response.Body.String())
		}
		for _, key := range []string{"Content-Length", "Content-Encoding", "Content-Disposition", "ETag"} {
			if response.Header().Get(key) != "" {
				t.Fatal("旧实体头污染错误响应", key)
			}
		}
	}
	allowed := []string{"PUT", "DELETE", "PUT", "bad\r\nX-Injected: yes"}
	problem := MethodNotSupported("GET", allowed)
	allowed[0] = "PATCH"
	response := httptest.NewRecorder()
	HandleErrors(func(http.ResponseWriter, *http.Request) error { return problem }, logger).ServeHTTP(response, httptest.NewRequest("GET", "/test", nil))
	if response.Header().Get("Allow") != "PUT, DELETE" || response.Header().Get("X-Injected") != "" {
		t.Fatal("Allow必须复制/去重且拒绝header注入", response.Header())
	}
}

type plainResponse struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (w *plainResponse) Header() http.Header             { return w.header }
func (w *plainResponse) WriteHeader(status int)          { w.status = status }
func (w *plainResponse) Write(value []byte) (int, error) { return w.body.Write(value) }

type hijackResponse struct {
	*plainResponse
	conn net.Conn
}

func (w *hijackResponse) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	return w.conn, bufio.NewReadWriter(bufio.NewReader(w.conn), bufio.NewWriter(w.conn)), nil
}

func TestExceptionBoundaryOptionalInterfacesAndHijack(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	plain := &plainResponse{header: http.Header{}}
	HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		if _, ok := w.(http.Flusher); ok {
			t.Fatal("不能虚构Flusher")
		}
		if _, ok := w.(http.Hijacker); ok {
			t.Fatal("不能虚构Hijacker")
		}
		if err := http.NewResponseController(w).Flush(); !errors.Is(err, http.ErrNotSupported) {
			t.Fatal(err)
		}
		return NotFound()
	}, logger).ServeHTTP(plain, httptest.NewRequest("GET", "/test", nil))
	if plain.status != 404 {
		t.Fatal("不支持Flush不能误标为已提交")
	}
	left, right := net.Pipe()
	defer right.Close()
	hijacked := &hijackResponse{plainResponse: &plainResponse{header: http.Header{}}, conn: left}
	func() {
		defer func() {
			if recover() != http.ErrAbortHandler {
				t.Fatal("Hijack后错误应中止")
			}
		}()
		HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
			conn, _, err := http.NewResponseController(w).Hijack()
			if err != nil {
				t.Fatal(err)
			}
			conn.Close()
			return errors.New("private-after-hijack")
		}, logger).ServeHTTP(hijacked, httptest.NewRequest("GET", "/test", nil))
		t.Fatal("未检测Hijack")
	}()
	if hijacked.status != 0 || hijacked.body.Len() != 0 {
		t.Fatal("Hijack后不能再写HTTP")
	}
}

func TestExceptionBoundaryInformationalResponseCanStillFail(t *testing.T) {
	server := httptest.NewServer(HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		w.WriteHeader(http.StatusEarlyHints)
		return MissingParameter("file")
	}, slog.New(slog.NewTextHandler(io.Discard, nil))))
	defer server.Close()
	response, err := server.Client().Get(server.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	body, err := io.ReadAll(response.Body)
	if err != nil || response.StatusCode != 400 || !bytes.Contains(body, []byte("缺少参数 file")) {
		t.Fatal("103不能当作最终响应", response.StatusCode, string(body), err)
	}
}
