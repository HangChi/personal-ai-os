package agent

import (
	"context"
	sdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"personal-ai-os/backend-go/internal/business/confirmation"
	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/mcp"
	"personal-ai-os/backend-go/internal/business/model"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type p7ConfirmStore struct {
	mu   sync.Mutex
	rows map[string]confirmation.Record
}

func (s *p7ConfirmStore) Create(_ context.Context, r confirmation.Record) (confirmation.Record, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r.ID = UUID()
	s.rows[r.ID] = r
	return r, nil
}
func (s *p7ConfirmStore) Get(_ context.Context, id string) (confirmation.Record, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.rows[id]
	return r, ok, nil
}
func (s *p7ConfirmStore) List(context.Context, string, confirmation.Status) ([]confirmation.Record, error) {
	return nil, nil
}
func (s *p7ConfirmStore) CompareAndSet(_ context.Context, id string, old, next confirmation.Status, at time.Time) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r := s.rows[id]
	if r.Status != old {
		return false, nil
	}
	r.Status = next
	s.rows[id] = r
	return true, nil
}

func TestP7MCPAuditBudgetConfirmationAndReconnect(t *testing.T) {
	var calls atomic.Int32
	server := sdk.NewServer(&sdk.Implementation{Name: "remote", Version: "1"}, nil)
	for _, name := range []string{"read", "write"} {
		server.AddTool(&sdk.Tool{Name: name, InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}}, func(context.Context, *sdk.CallToolRequest) (*sdk.CallToolResult, error) {
			calls.Add(1)
			return &sdk.CallToolResult{Content: []sdk.Content{&sdk.TextContent{Text: "done"}}}, nil
		})
	}
	upstream := httptest.NewServer(sdk.NewStreamableHTTPHandler(func(*http.Request) *sdk.Server { return server }, &sdk.StreamableHTTPOptions{Stateless: true, JSONResponse: true}))
	defer upstream.Close()
	registry, e := mcp.OpenRegistry(context.Background(), mcp.Options{Enabled: true, Name: "fixture", Version: "1", Servers: map[string]mcp.ServerConfig{"remote": {Endpoint: upstream.URL, AutoTools: []string{"read"}, Timeout: time.Second}}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if e != nil {
		t.Fatal(e)
	}
	defer registry.Close()
	store := &p7ConfirmStore{rows: map[string]confirmation.Record{}}
	handlers, _ := confirmation.NewRegistryFor([]confirmation.Action{confirmation.MCPToolCall}, &mcp.ConfirmationHandler{Registry: registry})
	confirms, _ := confirmation.NewService(store, handlers)
	audit := &memoryAudit{records: map[string]idempotency.Record{}}
	idem, _ := idempotency.NewService(audit)
	tools, e := NewRegistry(idem, nil, MCPBindings(registry, confirms))
	if e != nil {
		t.Fatal(e)
	}
	call := model.ToolCall{}
	call.Function.Name = "mcp_remote_write"
	call.Function.Arguments = `{"path":"/actual"}`
	first, e := tools.Execute(context.Background(), testTurn(), call)
	if e != nil || first.Status != "REQUIRES_CONFIRMATION" || first.ConfirmationID == nil || calls.Load() != 0 {
		t.Fatal(first, e, calls.Load())
	}
	replay, e := tools.Execute(context.Background(), testTurn(), call)
	if e != nil || replay.Status != "DUPLICATE" || len(store.rows) != 1 {
		t.Fatal(replay, e)
	}
	if e = registry.Reconnect(context.Background(), "remote"); e != nil {
		t.Fatal(e)
	}
	record, _, _ := store.Get(context.Background(), *first.ConfirmationID)
	var success atomic.Int32
	var wg sync.WaitGroup
	for range 12 {
		wg.Go(func() {
			result, e := confirms.Confirm(context.Background(), record.ID, record.Token)
			if e == nil && result.Executed {
				success.Add(1)
			}
		})
	}
	wg.Wait()
	if success.Load() != 1 || calls.Load() != 1 {
		t.Fatal("confirmation repeated", success.Load(), calls.Load())
	}
	call.Function.Name = "mcp_remote_read"
	call.Function.Arguments = "{"
	result, e := tools.Execute(context.Background(), testTurn(), call)
	if e != nil || result.Status != "OK" || calls.Load() != 2 {
		t.Fatal(result, e, calls.Load())
	}
	if len(audit.records) != 2 {
		t.Fatal("missing durable audit", len(audit.records))
	}
	call.Function.Arguments = "{}"
	replayedAuto, e := tools.Execute(context.Background(), testTurn(), call)
	if e != nil || replayedAuto.Status != "DUPLICATE" || calls.Load() != 2 || len(audit.records) != 2 {
		t.Fatal("AUTO call bypassed durable ownership", replayedAuto, e, calls.Load())
	}
	tools.TotalLimit = 1
	turn := testTurn()
	call.Function.Arguments = `{"path":"/another"}`
	if _, e := tools.Execute(context.Background(), turn, call); e != nil {
		t.Fatal(e)
	}
	call.Function.Name = "mcp_remote_write"
	call.Function.Arguments = `{"path":"/budget-blocked"}`
	if _, e := tools.Execute(context.Background(), turn, call); e == nil || calls.Load() != 3 || len(store.rows) != 1 || len(audit.records) != 3 {
		t.Fatal("external tools bypassed the common tool budget", e, calls.Load(), len(store.rows), len(audit.records))
	}
}

func TestMCPAutoDecrementReplaysAcrossFreshBudgetsAndReorderedArguments(t *testing.T) {
	var quantity atomic.Int32
	quantity.Store(3)
	var fail atomic.Bool
	identities := make(chan string, 8)
	server := sdk.NewServer(&sdk.Implementation{Name: "counter", Version: "1"}, nil)
	server.AddTool(&sdk.Tool{Name: "decrement", InputSchema: map[string]any{"type": "object", "properties": map[string]any{}}}, func(_ context.Context, request *sdk.CallToolRequest) (*sdk.CallToolResult, error) {
		quantity.Add(-1)
		identity, _ := request.Params.Meta[mcp.IdempotencyMetaKey].(string)
		identities <- identity
		return &sdk.CallToolResult{IsError: fail.Load(), Content: []sdk.Content{&sdk.TextContent{Text: "done"}}}, nil
	})
	upstream := httptest.NewServer(sdk.NewStreamableHTTPHandler(func(*http.Request) *sdk.Server { return server }, &sdk.StreamableHTTPOptions{Stateless: true, JSONResponse: true}))
	defer upstream.Close()
	remote, err := mcp.OpenRegistry(context.Background(), mcp.Options{Enabled: true, Name: "fixture", Version: "1", Servers: map[string]mcp.ServerConfig{"counter": {Endpoint: upstream.URL, AutoTools: []string{"decrement"}, Timeout: time.Second}}}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	defer remote.Close()
	audit := &memoryAudit{records: map[string]idempotency.Record{}}
	idem, _ := idempotency.NewService(audit)
	registry, err := NewRegistry(idem, nil, MCPBindings(remote, nil))
	if err != nil {
		t.Fatal(err)
	}
	call := model.ToolCall{}
	call.Function.Name = "mcp_counter_decrement"
	call.Function.Arguments = `{"quantity":1,"item":{"z":2,"a":1}}`
	first, err := registry.Execute(context.Background(), testTurn(), call)
	if err != nil || first.Status != "OK" {
		t.Fatal(first, err)
	}
	call.Function.Arguments = `{"item":{"a":1,"z":2},"quantity":1}`
	second, err := registry.Execute(context.Background(), testTurn(), call)
	if err != nil || second.Status != "DUPLICATE" || quantity.Load() != 2 {
		t.Fatal("decrement ran twice", second, err, quantity.Load())
	}
	if identity := <-identities; identity == "" {
		t.Fatal("AUTO call omitted stable operation metadata")
	}
	fail.Store(true)
	next := testTurn()
	next.ID = "next"
	failed, err := registry.Execute(context.Background(), next, call)
	if err != nil || failed.Status != "ERROR" || failed.Message != mcp.UnknownOutcome("decrement") {
		t.Fatal(failed, err)
	}
	if identity := <-identities; identity == "" {
		t.Fatal("failed call omitted identity")
	}
	retry := testTurn()
	retry.ID = "next"
	blocked, err := registry.Execute(context.Background(), retry, call)
	if err != nil || blocked.Status != "REJECTED" || quantity.Load() != 1 {
		t.Fatal("failed AUTO call reexecuted", blocked, err, quantity.Load())
	}

	store := &p7ConfirmStore{rows: map[string]confirmation.Record{}}
	handlers, _ := confirmation.NewRegistryFor([]confirmation.Action{confirmation.MCPToolCall}, &mcp.ConfirmationHandler{Registry: remote})
	confirms, _ := confirmation.NewService(store, handlers)
	dto, err := confirms.Create(context.Background(), confirmation.MCPToolCall, "扣减", mcp.ToolCall{Server: "counter", Tool: "decrement", Arguments: map[string]any{}}, 1, "c")
	if err != nil {
		t.Fatal(err)
	}
	_, err = confirms.Confirm(context.Background(), dto.ID, dto.Token)
	if err == nil || err.Error() != mcp.UnknownOutcome("decrement") {
		t.Fatal(err)
	}
	if identity := <-identities; identity != "confirmation:"+dto.ID {
		t.Fatal("decision identity changed", identity)
	}
	_, err = confirms.Confirm(context.Background(), dto.ID, dto.Token)
	if err == nil || quantity.Load() != 0 {
		t.Fatal("confirmation retried the remote side effect", err, quantity.Load())
	}
}
