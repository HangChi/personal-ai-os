package agent

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"
)

func TestPlanToolActionsPersistCorrectTransitionsAndReportProgress(t *testing.T) {
	ctx := context.Background()
	for _, name := range []string{"getPlan", "createPlan", "revisePlan", "startPlanStep", "completePlanStep", "failPlanStep", "skipPlanStep"} {
		t.Run(name, func(t *testing.T) {
			var steps []sqlStep
			args := map[string]any{"goal": "goal", "steps": []any{"one", "two"}, "seq": json.Number("1"), "summary": "receipt", "reason": "reason"}
			switch name {
			case "getPlan":
				steps = append(planReadSteps(false, stepRow(1, "DONE"), stepRow(2, "PENDING")), terminalStep("commit"))
			case "createPlan", "revisePlan":
				steps = append(planReadSteps(true, stepRow(1, "RUNNING")), execStep("status='ABORTED'"), execStep("INSERT INTO agent_plan("), execStep("INSERT INTO agent_plan_step"), execStep("INSERT INTO agent_plan_step"), terminalStep("commit"))
			default:
				steps = append(planReadSteps(true, stepRow(1, "RUNNING"), stepRow(2, "PENDING")), execStep("UPDATE agent_plan_step SET"), execStep("UPDATE agent_plan SET"), terminalStep("commit"))
				if name == "startPlanStep" {
					steps = append(planReadSteps(true, stepRow(1, "PENDING"), stepRow(2, "PENDING")), execStep("UPDATE agent_plan_step SET"), execStep("UPDATE agent_plan SET"), terminalStep("commit"))
				}
			}
			s := &Plans{Pool: contractPool(t, steps...)}
			for _, d := range s.Tools() {
				if d.Model.Name == name {
					v, e := d.Action(ctx, &Turn{ConversationID: "c"}, args)
					if e != nil || v.Status != "OK" || v.Data == nil || v.Message == "" {
						t.Fatal(v, e)
					}
				}
			}
		})
	}
	s := &Plans{}
	for _, d := range s.Tools() {
		switch d.Model.Name {
		case "createPlan", "revisePlan":
			for _, v := range []any{nil, []any{1}} {
				if _, e := d.Action(ctx, &Turn{}, map[string]any{"steps": v}); e == nil {
					t.Fatal(d.Model.Name, v)
				}
			}
		case "getPlan":
		default:
			for _, v := range []any{nil, "not-integer"} {
				if _, e := d.Action(ctx, &Turn{}, map[string]any{"seq": v}); e == nil {
					t.Fatal(d.Model.Name, v)
				}
			}
		}
	}
	if v, e := s.Prompt(ctx, ""); e != nil || v != "" {
		t.Fatal(v, e)
	}
	s = &Plans{Pool: contractPool(t, append(planReadSteps(true, stepRow(1, "DONE")), terminalStep("commit"))...)}
	if e := s.Success(ctx, "c", "tool", "receipt"); e != nil {
		t.Fatal(e)
	}
}

type webBoundaryTransport func(*http.Request) (*http.Response, error)

func (f webBoundaryTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func TestBraveSearchTransportFailuresAndToolOutcomesAreBounded(t *testing.T) {
	ctx := context.Background()
	for _, mode := range []string{"network", "cancel", "status", "json", "success"} {
		c, cancel := context.WithCancel(ctx)
		client := &http.Client{Transport: webBoundaryTransport(func(*http.Request) (*http.Response, error) {
			if mode == "network" {
				return nil, errors.New("private")
			}
			if mode == "cancel" {
				cancel()
				return nil, context.Canceled
			}
			code := 200
			if mode == "status" {
				code = 503
			}
			body := "{"
			if mode == "success" {
				body = `{"grounding":{"generic":[]}}`
			}
			return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader(body))}, nil
		})}
		_, e := braveSearch("synthetic", "https://example.test", client)(c, "query")
		cancel()
		if (e != nil) != (mode != "success") {
			t.Fatal(mode, e)
		}
	}
	for _, query := range []string{"", strings.Repeat("x", 501)} {
		if _, e := braveSearch("", "http://bad host", nil)(ctx, query); e == nil {
			t.Fatal(query)
		}
	}
	if _, e := braveSearch("", "http://bad host", nil)(ctx, "q"); e == nil {
		t.Fatal("endpoint")
	}
	for _, mode := range []string{"empty", "long", "no-registrar", "error", "cancel", "none", "success"} {
		c, cancel := context.WithCancel(ctx)
		turn := &Turn{}
		query := "q"
		if mode != "no-registrar" {
			turn.AddWebSources = func(s []WebSource) []WebSource { return s }
		}
		if mode == "empty" {
			query = ""
		}
		if mode == "long" {
			query = strings.Repeat("x", 501)
		}
		d := WebSearchTool(func(context.Context, string) ([]WebSource, error) {
			if mode == "error" {
				return nil, errors.New("private")
			}
			if mode == "cancel" {
				cancel()
				return nil, context.Canceled
			}
			if mode == "none" {
				return nil, nil
			}
			return []WebSource{{Title: "t"}}, nil
		})
		v, e := d.Action(c, turn, map[string]any{"query": query})
		cancel()
		if (e != nil) != (mode == "cancel") {
			t.Fatal(mode, e)
		}
		if e == nil && v.Status == "" {
			t.Fatal(v)
		}
	}
	// The constructor must be usable without starting network activity.
	if NewBraveSearch("synthetic") == nil {
		t.Fatal("constructor")
	}
}
