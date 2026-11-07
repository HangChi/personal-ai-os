package agent

import (
	"context"
	"errors"
	"strings"
	"testing"

	"personal-ai-os/backend-go/internal/platform/api"
)

func TestPlanToolReceiptsKeepFirstToolAndBoundEachResultAndHistory(t *testing.T) {
	for _, text := range []string{"", "回执", strings.Repeat("界", 400), strings.Repeat("界", 401), strings.Repeat("😀", 200), strings.Repeat("😀", 201)} {
		t.Run(text[:min(len(text), 12)], func(t *testing.T) {
			s := Step{}
			bindStep(&s, "findTasks", text)
			want := text
			if text == strings.Repeat("界", 401) {
				want = strings.Repeat("界", 400) + "…"
			}
			if text == strings.Repeat("😀", 201) {
				want = strings.Repeat("😀", 200) + "…"
			}
			if s.ToolName == nil || *s.ToolName != "findTasks" || s.ToolResult == nil || *s.ToolResult != want {
				t.Fatal(s)
			}
		})
	}
	first := "findTasks"
	empty, blank, receipt := "", "  ", "找到任务"
	for _, previous := range []*string{nil, &empty, &blank, &receipt} {
		s := Step{ToolName: &first, ToolResult: previous}
		bindStep(&s, "updateTask", "已更新")
		want := "updateTask：已更新"
		if previous != nil && *previous == "找到任务" {
			want = *previous + string(rune(10)) + want
		}
		if *s.ToolName != first || *s.ToolResult != want {
			t.Fatal(s)
		}
	}
	s := Step{}
	bindStep(&s, "findTasks", strings.Repeat("甲", 400))
	bindStep(&s, "updateTask", strings.Repeat("乙", 400))
	want := strings.Repeat("甲", 400) + string(rune(10)) + "updateTask：" + strings.Repeat("乙", 388) + "…"
	if *s.ToolResult != want || *s.ToolName != "findTasks" {
		t.Fatal(s)
	}
}

func TestPlanValidationRejectsInvalidTitlesBeforeAccessingDatabase(t *testing.T) {
	s := &Plans{}
	for _, titles := range [][]string{nil, {}, make([]string, 13), {""}, {"  "}, {strings.Repeat("a", 201)}, {strings.Repeat("😀", 101)}} {
		_, err := s.Create(context.Background(), "c", "目标", titles, false)
		var business *api.BusinessError
		if !errors.As(err, &business) || int(business.Code()) != 40000 {
			t.Fatal(titles, err)
		}
	}
	for _, input := range []string{"  目标  ", strings.Repeat("a", 200), strings.Repeat("😀", 100)} {
		result, err := validatedText(input, "标题", 200)
		if err != nil || result != strings.TrimSpace(input) {
			t.Fatal(result, err)
		}
	}
}

func TestPlanControlToolsNeverBecomeStepEvidence(t *testing.T) {
	s := &Plans{}
	for _, tool := range []string{"createPlan", "startPlanStep", "completePlanStep", "failPlanStep", "skipPlanStep", "revisePlan", "getPlan"} {
		if !planTool(tool) {
			t.Fatal(tool)
		}
		if err := s.Success(context.Background(), "c", tool, "控制计划"); err != nil {
			t.Fatal(err)
		}
		if hint, err := s.Failure(context.Background(), "c", tool, "失败"); err != nil || hint != "" {
			t.Fatal(hint, err)
		}
	}
	if planTool("findTasks") {
		t.Fatal("business tool treated as plan control")
	}
	if err := s.Success(context.Background(), "", "findTasks", "无会话"); err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		states []string
		first  int
	}{
		{nil, -1}, {[]string{"DONE", "SKIPPED"}, -1}, {[]string{"DONE", "FAILED", "PENDING"}, 2}, {[]string{"SKIPPED", "RUNNING"}, 2}, {[]string{"PENDING"}, 1},
	} {
		p := &Plan{}
		for i, state := range test.states {
			p.Steps = append(p.Steps, Step{Seq: i + 1, Status: state})
		}
		if firstOpen(p) != test.first {
			t.Fatal(p, firstOpen(p))
		}
	}
}
