package chat

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"personal-ai-os/backend-go/internal/platform/coordination"
	"personal-ai-os/backend-go/internal/platform/database"
	"personal-ai-os/backend-go/internal/platform/modelgate"
	"strings"
	"sync"
	"testing"
	"time"
)

// 仅模拟SQL边界返回值；锁、约束与隔离仍由独立PostgreSQL集成套件验证。
type lifecycleState struct {
	mu                           sync.Mutex
	status, request              string
	events                       []RunEvent
	calls                        map[string]int
	question, pending, uncertain bool
	finished                     chan struct{}
	once                         sync.Once
}

func (v *lifecycleState) respond(op, q string, a []driver.NamedValue) (sqlStep, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.calls[op+":"+q]++
	step := sqlStep{affected: 1}
	if op == "begin" || op == "commit" || op == "rollback" {
		return step, nil
	}
	if op == "exec" {
		switch {
		case strings.Contains(q, "INSERT INTO runtime_lease"), strings.Contains(q, "UPDATE runtime_lease"), strings.Contains(q, "pg_advisory_xact_lock"):
		case strings.Contains(q, "INSERT INTO chat_run("):
			v.status = "RUNNING"
			v.request = a[2].Value.(string)
		case strings.Contains(q, "INSERT INTO chat_run_event"):
			name, payload := "", ""
			if len(a) == 3 {
				name = a[1].Value.(string)
				payload = a[2].Value.(string)
			} else {
				name = "summary_only"
				payload = a[1].Value.(string)
			}
			v.events = append(v.events, RunEvent{ID: int64(len(v.events) + 1), Name: name, Payload: payload})
		case strings.Contains(q, "UPDATE chat_run SET status=$2"):
			v.status = a[1].Value.(string)
			v.once.Do(func() { close(v.finished) })
		case strings.Contains(q, "UPDATE chat_run SET status='RUNNING'"):
			v.status = "RUNNING"
		case strings.Contains(q, "UPDATE agent_followup"):
		default:
			return step, errors.New("unrecognized lifecycle exec: " + q)
		}
		return step, nil
	}
	if op != "query" {
		return step, errors.New("unexpected op")
	}
	switch {
	case strings.Contains(q, "WITH ready AS (SELECT slot"), strings.Contains(q, "SELECT slot FROM runtime_lease"):
		step.rows = [][]driver.Value{sqlValues(0)}
	case strings.Contains(q, "SELECT conversation_id,read_only_report"):
		step.rows = [][]driver.Value{sqlValues("contract", false)}
	case strings.Contains(q, "SELECT id,conversation_id,status,request FROM chat_run"):
		if v.status != "" {
			step.rows = [][]driver.Value{sqlValues("run", "contract", v.status, v.request)}
		}
	case strings.Contains(q, "SELECT id,name,payload FROM chat_run_event"):
		after := a[1].Value.(int64)
		for _, e := range v.events {
			if e.ID > after {
				step.rows = append(step.rows, sqlValues(e.ID, e.Name, e.Payload))
			}
		}
	case strings.Contains(q, "SELECT r.status,"):
		step.rows = [][]driver.Value{sqlValues(v.status, v.question)}
	case strings.Contains(q, "SELECT status FROM chat_run"):
		step.rows = [][]driver.Value{sqlValues(v.status)}
	case strings.Contains(q, "FROM agent_question WHERE"):
		if v.question {
			step.rows = [][]driver.Value{questionRow("PENDING", nil)}
		}
	case strings.Contains(q, "SELECT question,answer"), strings.Contains(q, "FROM agent_followup WHERE"), strings.Contains(q, "SELECT request,status,mode,conversation_id"), strings.Contains(q, "SELECT operation,outcome"), strings.Contains(q, "SELECT payload FROM chat_run_event"), strings.Contains(q, "SELECT r.id,r.conversation_id,r.status"):
	case strings.Contains(q, "SELECT id,conversation_id FROM chat_run"):
		if v.status == "RUNNING" {
			step.rows = [][]driver.Value{sqlValues("run", "contract")}
		}
	case strings.Contains(q, "SELECT EXISTS(SELECT 1 FROM confirmation"):
		step.rows = [][]driver.Value{sqlValues(v.pending)}
	case strings.Contains(q, "SELECT EXISTS(SELECT 1 FROM operation_record"):
		step.rows = [][]driver.Value{sqlValues(v.uncertain)}
	case strings.Contains(q, "SELECT EXISTS(SELECT 1 FROM chat_run"):
		exists := v.status != ""
		if strings.Contains(q, "status='RUNNING'") {
			exists = v.status == "RUNNING"
		}
		step.rows = [][]driver.Value{sqlValues(exists)}
	case strings.Contains(q, "SELECT EXISTS(SELECT 1 FROM agent_plan"):
		step.rows = [][]driver.Value{sqlValues(false)}
	default:
		return step, errors.New("unrecognized lifecycle query: " + q)
	}
	return step, nil
}

type serializedOracle struct {
	mu sync.Mutex
	tx Tx
}

func (s *serializedOracle) Transact(ctx context.Context, _ bool, fn func(Tx) error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if e := ctx.Err(); e != nil {
		return e
	}
	return fn(s.tx)
}

type lifecycleLimiter bool

func (v lifecycleLimiter) TryAcquire(context.Context) (bool, error) { return bool(v), nil }
func lifecycleFixture(t *testing.T, state *lifecycleState, decorators ...func(func(string, string, []driver.NamedValue) (sqlStep, error)) func(string, string, []driver.NamedValue) (sqlStep, error)) *Runs {
	t.Helper()
	state.calls = map[string]int{}
	state.finished = make(chan struct{})
	responder := state.respond
	for _, decorate := range decorators {
		responder = decorate(responder)
	}
	script := &sqlScript{t: t, responder: responder}
	pool, e := database.Open(context.Background(), sqlConnector{script}, database.DefaultPoolOptions(false))
	if e != nil {
		t.Fatal(e)
	}
	coordinator, e := coordination.New(pool)
	if e != nil {
		t.Fatal(e)
	}
	gate, e := modelgate.NewLocal(2)
	if e != nil {
		t.Fatal(e)
	}
	engine, tx, _ := engineFixture(t)
	store := &serializedOracle{tx: tx}
	service, e := NewService(store, nil)
	if e != nil {
		t.Fatal(e)
	}
	engine.Service = service
	engine.History.Store = store
	engine.Assembler.Service = service
	r := NewRuns(context.Background(), pool, engine, &Interactions{Pool: pool}, coordinator, gate, lifecycleLimiter(true), true)
	t.Cleanup(func() {
		_ = r.Close(context.Background())
		_ = coordinator.Close(context.Background())
		_ = pool.Close()
	})
	return r
}
func TestRunsCompleteLifecyclePersistsEventsAndReleasesCapacity(t *testing.T) {
	state := &lifecycleState{}
	r := lifecycleFixture(t, state)
	out, e := r.Start(context.Background(), "contract", "run", StreamRequest{Content: "目标"})
	if e != nil || out.ID != "run" {
		t.Fatal(out, e)
	}
	select {
	case <-state.finished:
	case <-time.After(3 * time.Second):
		t.Fatal("run did not finish")
	}
	if e = r.Close(context.Background()); e != nil {
		t.Fatal(e)
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.status != "COMPLETED" {
		t.Fatal(state.status, state.events, state.calls)
	}
	names := []string{}
	for _, v := range state.events {
		names = append(names, v.Name)
		if !json.Valid([]byte(v.Payload)) {
			t.Fatal(v)
		}
	}
	if !strings.Contains(strings.Join(names, ","), "done") || r.Engine.Metrics.Active.Load() != 0 || len(r.active) != 0 {
		t.Fatal(names, r.active)
	}
	first, e := r.Gate.TryAcquire(context.Background())
	if e != nil || first == nil {
		t.Fatal(e)
	}
	second, e := r.Gate.TryAcquire(context.Background())
	if e != nil || second == nil {
		t.Fatal(e)
	}
	_ = first.Release(context.Background())
	_ = second.Release(context.Background())
}
func TestRunsResumeAndSummaryLifecycleContract(t *testing.T) {
	for _, mode := range []string{"continue", "summary", "pending", "uncertain", "question"} {
		t.Run(mode, func(t *testing.T) {
			state := &lifecycleState{status: "INTERRUPTED", request: `{"content":"目标"}`, pending: mode == "pending", uncertain: mode == "uncertain", question: mode == "question"}
			r := lifecycleFixture(t, state)
			var e error
			if mode == "summary" {
				_, e = r.Summarize(context.Background(), "contract", "run")
			} else {
				_, e = r.Resume(context.Background(), "contract", "run", "")
			}
			fail := mode == "pending" || mode == "uncertain" || mode == "question"
			if (e != nil) != fail {
				t.Fatal(e)
			}
			if fail {
				return
			}
			select {
			case <-state.finished:
			case <-time.After(3 * time.Second):
				t.Fatal("resume did not finish")
			}
			_ = r.Close(context.Background())
			state.mu.Lock()
			defer state.mu.Unlock()
			want := "COMPLETED"
			if mode == "summary" {
				want = "INTERRUPTED"
			}
			if state.status != want {
				t.Fatal(state.status, state.events, state.calls)
			}
			found := false
			for _, v := range state.events {
				if v.Name == "reset" {
					found = true
				}
			}
			if !found {
				t.Fatal("recovery reset missing")
			}
		})
	}
}
