package chat

import (
	"bytes"
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/task"
)

func exportModuleRows() map[string][]driver.Value {
	at := time.Date(2026, 11, 7, 10, 0, 0, 0, time.UTC)
	id := "10000000-0000-4000-8000-000000000001"
	return map[string][]driver.Value{
		"calendarEvents": sqlValues(id, "合成日程", nil, "会议室", false, at, at.Add(time.Hour), "NONE", nil, 15, at),
		"memories":       sqlValues(id, "合成长期偏好", "AUTO", true, nil, at, at, 2, at),
		"projects":       sqlValues(id, "合成项目", nil, false, at, at),
		"tasks":          sqlValues(id, "合成任务", nil, "TODO", "MEDIUM", at, nil, nil, at, at, nil, nil, "NONE", "UTC", 0, false),
	}
}

func exportModules() []dataexport.Contributor {
	return []dataexport.Contributor{task.ExportContributor{}, task.ProjectExportContributor{}, memory.ExportContributor{}, calendar.ExportContributor{}}
}

func exportSelect(key string) string {
	return map[string]string{"calendarEvents": "FROM calendar_event ORDER BY id", "memories": "FROM memory ORDER BY id", "projects": "FROM project ORDER BY id", "tasks": "FROM task ORDER BY id"}[key]
}

func TestDefaultExportWritesSortedModulesAndNestedTaskTags(t *testing.T) {
	rows := exportModuleRows()
	steps := []sqlStep{terminalStep("begin")}
	for _, key := range []string{"calendarEvents", "memories", "projects", "tasks"} {
		q := queryStep(exportSelect(key), rows[key])
		q.check = func(a []driver.NamedValue) {
			if a[0].Value != int64(dataexport.BatchSize) || a[1].Value != int64(0) {
				t.Fatal(a)
			}
		}
		steps = append(steps, q)
	}
	steps = append(steps, queryStep("FROM task_tag", sqlValues("重要")), terminalStep("commit"))
	s := &dataexport.Service{Pool: contractPool(t, steps...), Name: "Personal AI OS", Version: "fixture", Contributors: exportModules()}
	var out bytes.Buffer
	if err := s.Write(context.Background(), &out); err != nil {
		t.Fatal(err)
	}
	var result struct {
		Application   string           `json:"application"`
		Version       string           `json:"version"`
		FormatVersion string           `json:"formatVersion"`
		ExportedAt    time.Time        `json:"exportedAt"`
		Counts        map[string]int   `json:"counts"`
		Memories      []memory.Entry   `json:"memories"`
		Events        []calendar.Event `json:"calendarEvents"`
		Projects      []task.Project   `json:"projects"`
		Tasks         []task.DTO       `json:"tasks"`
	}
	if err := json.Unmarshal(out.Bytes(), &result); err != nil {
		t.Fatal(err, out.String())
	}
	if result.Application != s.Name || result.Version != s.Version || result.FormatVersion != "1" || result.ExportedAt.IsZero() || len(result.Counts) != 4 || len(result.Memories) != 1 || len(result.Events) != 1 || len(result.Projects) != 1 || len(result.Tasks) != 1 {
		t.Fatalf("incomplete export: %#v", result)
	}
	for key, count := range result.Counts {
		if count != 1 {
			t.Fatalf("%s count=%d", key, count)
		}
	}
	if result.Memories[0].Source != "AUTO" || !result.Memories[0].Pinned || len(result.Tasks[0].Tags) != 1 || result.Tasks[0].Tags[0] != "重要" || result.Events[0].Title != "合成日程" || result.Projects[0].Name != "合成项目" {
		t.Fatal("export lost module fields", out.String())
	}
	// Sorting the export must not reorder the caller's registration slice.
	if s.Contributors[0].Key() != "tasks" {
		t.Fatal("registration order mutated")
	}
	previous := -1
	for _, key := range []string{"calendarEvents", "memories", "projects", "tasks"} {
		position := strings.Index(out.String(), `"`+key+`":[`)
		if position <= previous {
			t.Fatal("export modules are not sorted", out.String())
		}
		previous = position
	}
}

func TestDefaultExportPaginationAdvancesOffsetsWithoutDroppingEntries(t *testing.T) {
	row := exportModuleRows()["memories"]
	batch := make([][]driver.Value, dataexport.BatchSize)
	for i := range batch {
		batch[i] = append([]driver.Value(nil), row...)
		batch[i][0] = fmt.Sprintf("10000000-0000-4000-8000-%012d", i)
	}
	first := queryStep("FROM memory ORDER BY id", batch...)
	second := queryStep("FROM memory ORDER BY id", row)
	second.check = func(a []driver.NamedValue) {
		if a[1].Value != int64(dataexport.BatchSize) {
			t.Fatal("pagination did not advance", a)
		}
	}
	s := &dataexport.Service{Pool: contractPool(t, terminalStep("begin"), first, second, terminalStep("commit")), Contributors: []dataexport.Contributor{memory.ExportContributor{}}}
	var out bytes.Buffer
	if err := s.Write(context.Background(), &out); err != nil {
		t.Fatal(err)
	}
	var result struct {
		Memories []memory.Entry
		Counts   map[string]int
	}
	if err := json.Unmarshal(out.Bytes(), &result); err != nil || len(result.Memories) != dataexport.BatchSize+1 || result.Counts["memories"] != dataexport.BatchSize+1 {
		t.Fatal(result.Counts, err)
	}
	for i, entry := range result.Memories {
		wantID := row[0].(string)
		if i < dataexport.BatchSize {
			wantID = batch[i][0].(string)
		}
		if entry.ID != wantID {
			t.Fatal("pagination dropped, duplicated or reordered an entry", i, entry.ID, wantID)
		}
	}
}

type exportFailWriter struct{ err error }

func (w exportFailWriter) Write([]byte) (int, error) { return 0, w.err }

func TestDefaultExportFailuresRollbackAndNeverVisitLaterModules(t *testing.T) {
	failure := errors.New("synthetic export failure")
	for _, c := range exportModules() {
		for _, mode := range []string{"query", "scan", "rows", "writer", "tags"} {
			if mode == "tags" && c.Key() != "tasks" {
				continue
			}
			t.Run(c.Key()+"/"+mode, func(t *testing.T) {
				q := queryStep(exportSelect(c.Key()), exportModuleRows()[c.Key()])
				switch mode {
				case "query":
					q.err = failure
				case "scan":
					q.rows = [][]driver.Value{sqlValues(nil)}
				case "rows":
					q.rowErr = failure
				}
				steps := []sqlStep{terminalStep("begin"), q}
				if c.Key() == "tasks" && (mode == "writer" || mode == "tags") {
					tags := queryStep("FROM task_tag", sqlValues("标签"))
					if mode == "tags" {
						tags.err = failure
					}
					steps = append(steps, tags)
				}
				steps = append(steps, terminalStep("rollback"))
				s := &dataexport.Service{Pool: contractPool(t, steps...), Contributors: []dataexport.Contributor{c, exportLaterContributor{t}}}
				var out io.Writer = io.Discard
				if mode == "writer" {
					out = exportFailWriter{failure}
				}
				err := s.Write(context.Background(), out)
				if err == nil || mode != "scan" && !errors.Is(err, failure) {
					t.Fatal("export failure lost", err)
				}
			})
		}
	}
}

type exportLaterContributor struct{ t *testing.T }

func (exportLaterContributor) Key() string { return "zzz-later" }
func (c exportLaterContributor) Write(context.Context, *sql.Tx, *dataexport.Writer) (int, error) {
	c.t.Error("export continued after earlier module failed")
	return 0, nil
}

func TestDefaultExportEmptyContributorsKeepValidArraysAndZeroCounts(t *testing.T) {
	steps := []sqlStep{terminalStep("begin")}
	for _, key := range []string{"calendarEvents", "memories", "projects", "tasks"} {
		steps = append(steps, queryStep(exportSelect(key)))
	}
	steps = append(steps, terminalStep("commit"))
	s := &dataexport.Service{Pool: contractPool(t, steps...), Contributors: exportModules()}
	var out bytes.Buffer
	if err := s.Write(context.Background(), &out); err != nil {
		t.Fatal(err)
	}
	for _, c := range exportModules() {
		if !strings.Contains(out.String(), `"`+c.Key()+`":[]`) || !strings.Contains(out.String(), `"`+c.Key()+`":0`) {
			t.Fatal(out.String())
		}
	}
}
