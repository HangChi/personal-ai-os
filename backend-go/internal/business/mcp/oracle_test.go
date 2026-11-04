package mcp_test

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"personal-ai-os/backend-go/internal/business/dataimport"
	"personal-ai-os/backend-go/internal/business/mcp"
	"personal-ai-os/backend-go/internal/business/search"
	"reflect"
	"sort"
	"testing"
)

type p7Contributor struct {
	module      string
	fail, empty bool
}

func (c p7Contributor) ModuleName() string { return c.module }
func (p7Contributor) Kind() string         { return "fixture" }
func (c p7Contributor) Search(_ context.Context, keyword string, limit int) ([]search.Hit, error) {
	if c.fail {
		return nil, errors.New("synthetic module failure")
	}
	if c.empty {
		return []search.Hit{}, nil
	}
	return []search.Hit{{Kind: "fixture", ID: keyword, Title: fmt.Sprintf("limit:%d", limit)}}, nil
}

func TestJavaP7NamingPolicySummarySearchPersonalAndImportOracle(t *testing.T) {
	raw, e := os.ReadFile("testdata/java-p7.json")
	if e != nil {
		t.Fatal(e)
	}
	var f struct {
		Inputs struct {
			Names    [][2]string
			Policies []struct {
				Name      string
				Automatic []string
			}
			Summaries []mcp.ToolCall
			Patterns  []string
			Searches  []struct {
				Keyword string
				Limit   int
			}
			Imports []string
		}
		Expected map[string][]json.RawMessage
	}
	if e := json.Unmarshal(raw, &f); e != nil {
		t.Fatal(e)
	}
	check := func(kind string, i int, got any) {
		t.Helper()
		raw, e := json.Marshal(got)
		if e != nil {
			t.Fatal(e)
		}
		var actual, expected any
		if e := json.Unmarshal(raw, &actual); e != nil {
			t.Fatal(e)
		}
		if e := json.Unmarshal(f.Expected[kind][i], &expected); e != nil {
			t.Fatal(e)
		}
		if !reflect.DeepEqual(actual, expected) {
			t.Fatalf("%s[%d]: %s != %s", kind, i, raw, f.Expected[kind][i])
		}
	}
	for i, row := range f.Inputs.Names {
		check("names", i, mcp.PrefixedName(row[0], row[1]))
	}
	for i, row := range f.Inputs.Policies {
		check("policies", i, mcp.Policy(row.Name, row.Automatic))
	}
	for i, value := range mcp.Conflicts([]string{"mcp_a_t"}, []string{"mcp_a_t", "mcp_a_t", "mcp_b_t", "mcp_b_t"}) {
		check("conflicts", i, value)
	}
	for i, row := range f.Inputs.Summaries {
		check("summaries", i, mcp.Summary(row))
	}
	for i, row := range f.Inputs.Patterns {
		check("patterns", i, search.Pattern(row))
	}
	s := search.Service{Contributors: []search.Contributor{p7Contributor{"记忆", false, false}, p7Contributor{"任务", false, false}, p7Contributor{"日程", true, false}, p7Contributor{"空", false, true}, p7Contributor{"对话", false, false}}}
	for i, row := range f.Inputs.Searches {
		value, e := s.Search(context.Background(), row.Keyword, row.Limit)
		if e != nil {
			check("searches", i, map[string]any{"accepted": false, "message": e.Error()})
		} else {
			check("searches", i, map[string]any{"accepted": true, "result": value})
		}
	}
	tools := (mcp.PersonalOptions{}).Status().Tools
	sort.Slice(tools, func(i, j int) bool { return tools[i].Name < tools[j].Name })
	for i, tool := range tools {
		check("personalTools", i, tool)
	}
	for i, row := range f.Inputs.Imports {
		value, e := dataimport.Parse([]byte(row))
		if e != nil {
			check("imports", i, map[string]any{"accepted": false})
			continue
		}
		tasks := []any{}
		for _, task := range value.Tasks {
			if task == nil {
				tasks = append(tasks, nil)
				continue
			}
			tags := task.Tags
			if tags == nil {
				tags = []string{}
			}
			tasks = append(tasks, map[string]any{"id": task.ID, "title": task.Title, "description": task.Description, "status": task.Status, "priority": task.Priority, "tags": tags})
		}
		var lead *int
		allDay, pinned, hits, size := false, false, 0, int64(0)
		if len(value.CalendarEvents) > 0 {
			allDay, lead = value.CalendarEvents[0].AllDay, value.CalendarEvents[0].Lead
		}
		if len(value.Memories) > 0 {
			pinned, hits = value.Memories[0].Pinned, value.Memories[0].HitCount
		}
		if len(value.Documents) > 0 && value.Documents[0].Document != nil {
			size = value.Documents[0].Document.SizeBytes
		}
		check("imports", i, map[string]any{"accepted": true, "tasks": tasks, "allDay": allDay, "lead": lead, "pinned": pinned, "hits": hits, "size": size})
	}
}
