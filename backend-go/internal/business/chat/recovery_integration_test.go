//go:build integration

package chat

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
)

func TestPostgresAutomaticRecoveryClosesWithoutReplayingWrites(t *testing.T) {
	for _, mode := range []string{"answer", "unavailable", "tool-call"} {
		t.Run(mode, func(t *testing.T) {
			var calls atomic.Int32
			title := "exactly-once-recovery-" + mode
			f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
				var body struct{ Tools []json.RawMessage }
				if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
					t.Error(err)
				}
				args, _ := json.Marshal(map[string]string{"title": title})
				if calls.Add(1) == 1 {
					emitTool(w, "createTask", string(args))
					return
				}
				if len(body.Tools) != 0 {
					t.Error("automatic recovery advertised tools")
				}
				switch mode {
				case "unavailable":
					w.WriteHeader(503)
				case "tool-call":
					emitTool(w, "createTask", string(args))
				default:
					emitText(w, "任务已创建，后续查询未完成。")
				}
			}, map[string]string{"app.agent.max-model-calls": "1"})
			ctx := context.Background()
			s := f.module.Runs
			c := fixtureSession(t, f.module.Service, "automatic recovery").ConversationID
			run, err := s.Start(ctx, c, "automatic-"+mode, StreamRequest{Content: "创建任务并查询后续信息"})
			if err != nil {
				t.Fatal(err)
			}
			done := waitRun(t, s, c, run.ID, "COMPLETED")
			var count int
			err = f.pool.WithConn(ctx, func(conn *sql.Conn) error {
				return conn.QueryRowContext(ctx, "SELECT count(*) FROM task WHERE title=$1", title).Scan(&count)
			})
			if err != nil || count != 1 || calls.Load() != 2 {
				t.Fatal("write or closing request repeated", count, calls.Load(), err)
			}
			warning, finished := false, false
			for _, event := range done.Events {
				if event.Name == "error" || event.Name == "reset" {
					t.Fatal("recoverable failure became terminal", event)
				}
				if event.Name == "warning" && strings.Contains(event.Payload, "AGENT_RECOVERY") {
					warning = true
				}
				if event.Name == "done" {
					finished = true
				}
			}
			if !warning || !finished {
				t.Fatal(done)
			}
			messages, err := f.module.Service.Messages(ctx, c, nil, nil)
			if err != nil || len(messages) != 2 {
				t.Fatal(messages, err)
			}
			answer := messages[1].Content
			if mode == "answer" && !strings.Contains(answer, "任务已创建") {
				t.Fatal(answer)
			}
			if mode != "answer" && !strings.Contains(answer, "createTask") {
				t.Fatal(answer)
			}
		})
	}
}

func TestPostgresAutomaticRecoveryAfterUpstreamFailure(t *testing.T) {
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			w.WriteHeader(503)
			return
		}
		var body struct{ Tools []json.RawMessage }
		_ = json.NewDecoder(r.Body).Decode(&body)
		if len(body.Tools) != 0 {
			t.Error("recovery advertised tools")
		}
		emitText(w, "模型服务暂时不可用，本轮没有执行任何操作。")
	})
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "upstream fallback").ConversationID
	run, err := s.Start(context.Background(), c, "automatic-upstream", StreamRequest{Content: "查询任务"})
	if err != nil {
		t.Fatal(err)
	}
	done := waitRun(t, s, c, run.ID, "COMPLETED")
	if calls.Load() != 2 {
		t.Fatal(calls.Load())
	}
	for _, event := range done.Events {
		if event.Name == "error" {
			t.Fatal(event)
		}
	}
}

func TestPostgresAutomaticRecoveryCanBeStopped(t *testing.T) {
	entered := make(chan struct{}, 1)
	var calls atomic.Int32
	f := newExecutionFixture(t, func(w http.ResponseWriter, r *http.Request) {
		if calls.Add(1) == 1 {
			w.WriteHeader(503)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		entered <- struct{}{}
		<-r.Context().Done()
	})
	s := f.module.Runs
	c := fixtureSession(t, f.module.Service, "stop recovery").ConversationID
	run, err := s.Start(context.Background(), c, "stop-automatic", StreamRequest{Content: "查询任务"})
	if err != nil {
		t.Fatal(err)
	}
	<-entered
	if err = s.Stop(context.Background(), c, run.ID); err != nil {
		t.Fatal(err)
	}
	stopped := waitRun(t, s, c, run.ID, "STOPPED")
	for _, event := range stopped.Events {
		if event.Name == "done" {
			t.Fatal("stop completed automatic summary", event)
		}
	}
	if calls.Load() != 2 {
		t.Fatal(calls.Load())
	}
}
