package task

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
)

// 摘要来自固定 Java 基线的 JsonCodec/UpdateTaskRequest 实际执行结果，
// 不能从 Go DTO 反推；Boolean 的 null 与 false 属于不同请求。
const javaPatchNullDigest = "03d482d74dad6316524418e1be57e570be64fa687e8bf729ce85c4eca8f1adca"

func TestPatchDigestMatchesJavaNullableBoolean(t *testing.T) {
	for _, tc := range []struct{ name, body, digest string }{
		{"omitted", `{"title":"renamed"}`, javaPatchNullDigest},
		{"null", `{"title":"renamed","clearDueAt":null}`, javaPatchNullDigest},
		{"false", `{"title":"renamed","clearDueAt":false}`, "34c1ccbec25c55a78a223e56d1ccab2a8ecc1a99317bcd3cf4becbb70eb13526"},
		{"true", `{"title":"renamed","clearDueAt":true}`, "b4b47668405d73f08632c58c15bfde8f8aed3b08e29d7be5647b2917260bdaa4"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPatch, "/api/v1/tasks/"+bindingTaskID, strings.NewReader(tc.body))
			r.Header.Set("Content-Type", "application/json")
			var request UpdateRequest
			if err := decodeTaskJSON(httptest.NewRecorder(), r, &request, []string{"title", "clearDueAt"}); err != nil {
				t.Fatal(err)
			}
			digest, err := idempotency.Digest(request)
			if err != nil || digest != tc.digest {
				t.Fatalf("digest=%s want Java %s err=%v", digest, tc.digest, err)
			}
		})
	}
}

const bindingTaskID = "00000000-0000-0000-0000-000000000001"

type legacyReplayStore struct{ idempotency.Store }

func (legacyReplayStore) Find(context.Context, string) (idempotency.Record, bool, error) {
	payload := `{"id":"` + bindingTaskID + `","title":"renamed"}`
	return idempotency.Record{Digest: javaPatchNullDigest, Outcome: idempotency.Success, ResponsePayload: &payload}, true, nil
}

func TestPatchReplaysJavaDigestWithoutExecutingAgain(t *testing.T) {
	idem, err := idempotency.NewService(legacyReplayStore{})
	if err != nil {
		t.Fatal(err)
	}
	// 任务服务没有仓储：必须只回放原结果，不能再次进入任务写路径。
	h, err := NewHTTP(new(Service), idem, new(confirmation.Service), slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		body   string
		status int
	}{
		{`{"title":"renamed"}`, http.StatusOK},
		{`{"title":"renamed","clearDueAt":null}`, http.StatusOK},
		{`{"title":"renamed","clearDueAt":false}`, http.StatusConflict},
	} {
		r := httptest.NewRequest(http.MethodPatch, "/api/v1/tasks/"+bindingTaskID, strings.NewReader(tc.body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Idempotency-Key", "java-patch-replay-001")
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		var result struct {
			Code int                        `json:"code"`
			Data struct{ ID, Title string } `json:"data"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		if w.Code != tc.status {
			t.Fatalf("body=%s status=%d response=%s", tc.body, w.Code, w.Body.String())
		}
		if tc.status == http.StatusOK && (result.Code != 0 || result.Data.ID != bindingTaskID || result.Data.Title != "renamed") {
			t.Fatalf("replay=%s", w.Body.String())
		}
		if tc.status == http.StatusConflict && result.Code != 40001 {
			t.Fatalf("digest conflict=%s", w.Body.String())
		}
	}
}
