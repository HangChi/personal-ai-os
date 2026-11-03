package api

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"
)

// fixture 来自固定 Java Git blob，不从 Go 实现反向生成期望值。
func TestJavaErrorCodeSnapshot(t *testing.T) {
	data, err := os.ReadFile("testdata/java-error-codes.json")
	if err != nil {
		t.Fatal(err)
	}
	var snapshot struct {
		Errors []struct {
			Name    string `json:"name"`
			Code    int    `json:"code"`
			Message string `json:"message"`
			Status  int    `json:"status"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(data, &snapshot); err != nil {
		t.Fatal(err)
	}
	names := map[string]ErrorCode{
		"SUCCESS": Success, "VALIDATION_FAILED": ValidationFailed, "IDEMPOTENCY_KEY_CONFLICT": IdempotencyKeyConflict,
		"CONFIRMATION_NOT_ACTIVE": ConfirmationNotActive, "UNSUPPORTED_DOCUMENT": UnsupportedDocument,
		"RESOURCE_NOT_FOUND": ResourceNotFound, "METHOD_NOT_ALLOWED": MethodNotAllowed, "NOT_ACCEPTABLE": NotAcceptable,
		"UNAUTHORIZED": Unauthorized, "FORBIDDEN": Forbidden, "BUSINESS_CONFLICT": BusinessConflict,
		"RATE_LIMITED": RateLimited, "CONTEXT_TOO_LARGE": ContextTooLarge, "AI_UPSTREAM_ERROR": AIUpstreamError,
		"EMBEDDING_DIMENSION_MISMATCH": EmbeddingDimensionMismatch, "AI_TIMEOUT": AITimeout,
		"AGENT_EXECUTION_LIMIT": AgentExecutionLimit, "AI_NOT_CONFIGURED": AINotConfigured, "INTERNAL_ERROR": InternalError,
	}
	if len(snapshot.Errors) != len(names) {
		t.Fatalf("Java/Go 错误码数量不一致：%d/%d", len(snapshot.Errors), len(names))
	}
	for _, expected := range snapshot.Errors {
		t.Run(expected.Name, func(t *testing.T) {
			code, found := names[expected.Name]
			if !found || int(code) != expected.Code || code.Message() != expected.Message || code.HTTPStatus() != expected.Status {
				t.Fatalf("Java/Go 错误映射不一致：%+v，Go=%v/%s/%d", expected, code, code.Message(), code.HTTPStatus())
			}
		})
	}
}

func TestResponseWireContract(t *testing.T) {
	before := time.Now()
	response := OK(map[string]string{"title": "中文任务"})
	if !response.IsSuccess() || response.Timestamp.Before(before) || response.Timestamp.After(time.Now()) {
		t.Fatal("成功状态或时间戳不正确")
	}
	data, err := json.Marshal(response)
	if err != nil {
		t.Fatal(err)
	}
	var body map[string]json.RawMessage
	if err := json.Unmarshal(data, &body); err != nil {
		t.Fatal(err)
	}
	if len(body) != 4 || string(body["code"]) != "0" || string(body["message"]) != `"OK"` || !strings.HasSuffix(string(body["timestamp"]), `Z"`) {
		t.Fatalf("信封字段发生变化：%s", data)
	}
	for _, value := range []Response[any]{OK[any](nil), Failure(InternalError), FailureMessage(ValidationFailed, "标题不能为空")} {
		encoded, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(encoded), `"data":null`) {
			t.Fatalf("空 data 不得省略：%s", encoded)
		}
	}
	if Failure(InternalError).IsSuccess() || FailureMessage(ValidationFailed, "标题不能为空").Message != "标题不能为空" {
		t.Fatal("失败状态或自定义消息错误")
	}
}

func TestSerializationFailureIsSafe(t *testing.T) {
	writer := httptest.NewRecorder()
	err := WriteJSON(writer, 200, map[string]any{"private": make(chan int)})
	if err == nil || writer.Code != 500 {
		t.Fatal("序列化失败不得保留 HTTP 200")
	}
	var failure Response[any]
	if err := json.Unmarshal(writer.Body.Bytes(), &failure); err != nil {
		t.Fatal(err)
	}
	if failure.Code != InternalError || failure.Message != "服务器内部错误" || failure.Data != nil || strings.Contains(writer.Body.String(), "private") {
		t.Fatalf("错误响应泄漏内部信息：%s", writer.Body.String())
	}
}

func TestPaginationBoundaries(t *testing.T) {
	all := []string{"a", "b", "c", "d", "e"}
	maxInt := int(^uint(0) >> 1)
	for _, test := range []struct {
		name       string
		page, size int
		want       []string
	}{
		{"first", 0, 2, []string{"a", "b"}},
		{"second", 1, 2, []string{"c", "d"}},
		{"last", 2, 2, []string{"e"}},
		{"out-of-range", 3, 2, []string{}},
		{"negative", -1, 0, []string{"a"}},
		{"int-overflow", maxInt, maxInt, []string{}},
	} {
		t.Run(test.name, func(t *testing.T) {
			page := PageOf(all, test.page, test.size)
			if !reflect.DeepEqual(page.Items, test.want) || page.Total != 5 || page.Page != max(test.page, 0) || page.Size != max(test.size, 1) {
				t.Fatalf("分页结果不正确：%+v", page)
			}
		})
	}
	encoded, err := json.Marshal(PageOf[string](nil, 0, 10))
	if err != nil || !strings.Contains(string(encoded), `"items":[]`) {
		t.Fatalf("空列表必须编码为 []：%s，%v", encoded, err)
	}
}

func TestSQLResultIsNotPaginatedTwice(t *testing.T) {
	items := []string{"ninth", "tenth"}
	page := PageOfResult(items, 4, 2, 20)
	items[0] = "modified"
	if !reflect.DeepEqual(page.Items, []string{"ninth", "tenth"}) || page.Page != 4 || page.Total != 20 {
		t.Fatalf("SQL 页被重复切片或与输入共享可变切片：%+v", page)
	}
	for _, test := range []struct {
		page, size int
		want       bool
	}{{21474836, 100, false}, {21474837, 100, true}, {2147483647, 1, false}, {2147483647, 2, true}, {-1, 100, false}, {100, -1, false}} {
		if got := OffsetOverflows(test.page, test.size); got != test.want {
			t.Errorf("offset(%d,%d)=%v，期望 %v", test.page, test.size, got, test.want)
		}
	}
}
