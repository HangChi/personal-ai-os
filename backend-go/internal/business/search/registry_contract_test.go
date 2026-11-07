package search

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/knowledge"
	"personal-ai-os/backend-go/internal/business/task"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/coordination"
)

type registryTasks struct {
	task.Store
	t   *testing.T
	err error
}

func (s registryTasks) SearchHits(_ context.Context, query string, limit int) ([]task.Task, error) {
	if query != "关键字" || limit != 1 {
		s.t.Fatal("task search scope lost", query, limit)
	}
	if s.err != nil {
		return nil, s.err
	}
	return []task.Task{{ID: "task-id", Title: "关键字任务", Status: task.Doing, Priority: task.High}}, nil
}

func TestDefaultSearchRegistryWiresEveryModuleAndPropagatesFailures(t *testing.T) {
	for _, failed := range []bool{false, true} {
		t.Run(map[bool]string{false: "success", true: "storage-failure"}[failed], func(t *testing.T) {
			failure := errors.New("synthetic contributor failure")
			steps := []sqlStep{queryStep("SELECT format_type", sqlValues("vector(2)")), queryStep("SELECT count(*)", sqlValues(0))}
			queries := []sqlStep{
				queryStep("FROM chat_session", sqlValues("session", "chat-id", "关键字对话", "1 条消息", "标题")),
				queryStep("FROM memory", sqlValues("memory-id", "关键字记忆", time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC))),
				queryStep("FROM calendar_event", sqlValues("event-id", "关键字日程", nil, nil, time.Date(2026, 11, 7, 0, 0, 0, 0, time.UTC), "NONE")),
				queryStep("FROM knowledge_document", sqlValues("document", "doc-id", "关键字文档", "1 个分块", "标题")),
			}
			for _, q := range queries {
				end := "commit"
				if failed {
					q.err = failure
					end = "rollback"
				}
				steps = append(steps, terminalStep("begin"), q, terminalStep(end))
			}
			pool := contractPool(t, steps...)
			store, err := knowledge.NewStore(context.Background(), pool, knowledge.Reader{Properties: config.KnowledgeProperties{EmbeddingDimensions: 2}}, nil, &coordination.Coordinator{}, "public", "vector_store")
			if err != nil {
				t.Fatal(err)
			}
			taskStore := registryTasks{t: t}
			if failed {
				taskStore.err = failure
			}
			tasks, err := task.NewService(taskStore)
			if err != nil {
				t.Fatal(err)
			}
			contributors := Contributors(pool, tasks, store)
			var names, kinds []string
			for _, c := range contributors {
				names = append(names, c.ModuleName())
				kinds = append(kinds, c.Kind())
				hits, err := c.Search(context.Background(), "关键字", 1)
				if failed {
					if !errors.Is(err, failure) {
						t.Fatal(c.Kind(), err)
					}
					continue
				}
				field := "标题"
				if c.Kind() == "memory" {
					field = "内容"
				}
				if err != nil || len(hits) != 1 || hits[0].Kind != c.Kind() || hits[0].MatchedField != field || !strings.Contains(hits[0].Title, "关键字") {
					t.Fatal(c.Kind(), hits, err)
				}
				if c.Kind() == "task" && hits[0].Subtitle != "进行中 · 高" {
					t.Fatal("task conversion lost status/priority", hits)
				}
			}
			if !reflect.DeepEqual(names, []string{"任务", "对话", "记忆", "日程", "知识库"}) || !reflect.DeepEqual(kinds, []string{"task", "session", "memory", "event", "document"}) {
				t.Fatal(names, kinds)
			}
		})
	}
}
