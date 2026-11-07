package memory

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"reflect"
	"strings"
	"testing"
	"time"
	"unicode/utf16"

	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

func TestMemoryPromptReadsRanksAndRefreshesOnlySelectedIDs(t *testing.T) {
	ctx := context.Background()
	settings := config.MemoryProperties{PinnedMaxCount: 1}
	settings.Ranking.Enabled = true
	settings.Ranking.Timeout = time.Second
	settings.Ranking.HalfLifeDays = 30
	first, second := memoryRow(), memoryRow()
	first[3], second[0], second[1], second[3] = false, "20000000-0000-4000-8000-000000000002", "相关长期偏好", false
	read := queryStep("superseded_by IS NULL ORDER BY updated_at DESC LIMIT 120", first, second)
	similarity := queryStep("1-(embedding <=>", sqlValues(memoryID, 0.1), sqlValues(second[0], 0.9))
	similarity.check = func(a []driver.NamedValue) {
		if a[1].Value != "fixture-embedding" || a[2].Value != int64(2) {
			t.Fatalf("unexpected ranking arguments: %#v", a)
		}
	}
	refresh := execStep("hit_count=hit_count+1")
	refresh.check = func(a []driver.NamedValue) {
		if !reflect.DeepEqual(a[0].Value, []string{second[0].(string), memoryID}) || a[1].Value.(time.Time).Sub(a[2].Value.(time.Time)) != 10*time.Minute {
			t.Fatalf("refresh lost ranking or cutoff: %#v", a)
		}
	}
	s := &Store{pool: contractPool(t, read, similarity, refresh)}
	var embedded string
	s.UseEmbedding(embeddingFunc(func(_ context.Context, req model.EmbeddingRequest) (model.EmbeddingResponse, error) {
		embedded = req.Input[0]
		return validMemoryEmbedding()(ctx, req)
	}), "fixture-embedding", .9)
	block, err := s.Prompt(ctx, strings.Repeat("😀", 120), settings)
	if err != nil || !strings.HasPrefix(block, promptHeader) || strings.Index(block, "相关长期偏好") >= strings.Index(block, "记忆内容") || len(utf16.Encode([]rune(embedded))) != 200 {
		t.Fatal(block, embedded, err)
	}
}

func TestMemoryPromptStorageFaultsAndBestEffortRefresh(t *testing.T) {
	failure := errors.New("synthetic memory storage failure")
	for _, mode := range []string{"empty", "query", "scan", "rows", "refresh", "ranking-fallback"} {
		t.Run(mode, func(t *testing.T) {
			read := queryStep("superseded_by IS NULL ORDER BY", memoryRow())
			steps := []sqlStep{read}
			switch mode {
			case "empty":
				steps[0].rows = nil
			case "query":
				steps[0].err = failure
			case "scan":
				steps[0].rows = [][]driver.Value{sqlValues(nil)}
			case "rows":
				steps[0].rowErr = failure
			case "refresh":
				write := execStep("hit_count=hit_count+1")
				write.err = failure
				steps = append(steps, write)
			case "ranking-fallback":
				steps = append(steps, sqlStep{op: "query", match: "1-(embedding <=>", err: failure}, execStep("hit_count=hit_count+1"))
			}
			s := &Store{pool: contractPool(t, steps...)}
			settings := config.MemoryProperties{PinnedMaxCount: 1}
			if mode == "ranking-fallback" {
				settings.Ranking.Enabled, settings.Ranking.Timeout = true, time.Second
				s.UseEmbedding(validMemoryEmbedding(), "fixture", .9)
			}
			block, err := s.Prompt(context.Background(), "合成查询", settings)
			if mode == "query" || mode == "scan" || mode == "rows" {
				if err == nil || block != "" {
					t.Fatal("failed read produced a prompt", block, err)
				}
			} else if err != nil || (mode == "empty") != (block == "") {
				t.Fatal("optional ranking/refresh blocked prompt", block, err)
			}
		})
	}
}

type captureTransport func(*http.Request) (*http.Response, error)

func (f captureTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func captureRuntime(t *testing.T, transport http.RoundTripper) *model.Runtime {
	t.Helper()
	properties, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{
		"spring.ai.openai.base-url": "https://capture.invalid", "spring.ai.openai.api-key": "synthetic", "spring.ai.openai.chat.model": "fixture",
	}})
	if err != nil {
		t.Fatal(err)
	}
	resolver, err := model.NewResolver(properties)
	if err != nil {
		t.Fatal(err)
	}
	runtime := model.NewRuntime(transport)
	if err = runtime.Apply(resolver.Resolve(model.Values{})); err != nil {
		t.Fatal(err)
	}
	return runtime
}

func TestMemoryCaptureUsesToolFreeModelAndRetainsIndependentSaveFailures(t *testing.T) {
	failure := errors.New("synthetic first save failure")
	first := queryStep("FROM memory WHERE content_hash=$1")
	first.err = failure
	second := queryStep("FROM memory WHERE content_hash=$1", memoryRow())
	second.check = func(a []driver.NamedValue) {
		if a[0].Value != contentHash("用户偏好使用中文") {
			t.Fatal("capture stopped before second fact", a)
		}
	}
	s := &Store{pool: contractPool(t, first, second)}
	calls := 0
	runtime := captureRuntime(t, captureTransport(func(r *http.Request) (*http.Response, error) {
		calls++
		defer r.Body.Close()
		var request struct {
			Messages []model.Message `json:"messages"`
			Tools    []any           `json:"tools"`
		}
		if err := json.NewDecoder(r.Body).Decode(&request); err != nil {
			t.Fatal(err)
		}
		if len(request.Tools) != 0 || len(request.Messages) != 2 || request.Messages[0].Content != captureSystem || request.Messages[1].Content != "用户：请记住偏好\n助手：已记录" {
			t.Fatalf("unexpected capture request: %#v", request)
		}
		raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": `["用户习惯使用深色主题","用户偏好使用中文","超过数量限制的偏好"]`}}}})
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(string(raw)))}, nil
	}))
	if err := s.Capture(context.Background(), runtime, "请记住偏好", "已记录", 2); !errors.Is(err, failure) || calls != 1 {
		t.Fatal("save error lost or model repeated", err, calls)
	}
}

func TestMemoryCaptureNoOpAndModelFailuresNeverWrite(t *testing.T) {
	for _, tc := range []struct {
		user, reply string
		limit       int
	}{{" ", "answer", 2}, {"question", " ", 2}, {"question", "answer", 0}} {
		if err := (&Store{}).Capture(context.Background(), nil, tc.user, tc.reply, tc.limit); err != nil {
			t.Fatal(err)
		}
	}
	for _, mode := range []string{"unconfigured", "network", "invalid-facts", "empty-facts"} {
		t.Run(mode, func(t *testing.T) {
			runtime := model.NewRuntime(nil)
			if mode != "unconfigured" {
				runtime = captureRuntime(t, captureTransport(func(r *http.Request) (*http.Response, error) {
					if mode == "network" {
						return nil, errors.New("synthetic upstream failure")
					}
					content := `[{"unsupported":"object"}]`
					if mode == "empty-facts" {
						content = `[]`
					}
					raw, _ := json.Marshal(map[string]any{"choices": []any{map[string]any{"message": map[string]string{"content": content}}}})
					return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(string(raw)))}, nil
				}))
			}
			s := &Store{pool: contractPool(t)}
			err := s.Capture(context.Background(), runtime, "合成用户偏好", "合成回复", 3)
			if (err != nil) != (mode == "unconfigured" || mode == "network") {
				t.Fatal(mode, err)
			}
		})
	}
}
