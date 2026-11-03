package task

import (
	"testing"
	"time"
)

func TestCompletionRestoresPreviousStatus(t *testing.T) {
	now := time.Now()
	task := Task{Status: Doing}
	if err := task.SetCompleted(true, now); err != nil {
		t.Fatal(err)
	}
	if task.Status != Done || task.CompletedAt == nil || task.ReopenTarget() != Doing {
		t.Fatalf("completed=%+v", task)
	}
	if err := task.SetCompleted(true, now.Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if task.ReopenTarget() != Doing {
		t.Fatal("repeat completion overwrote previous status")
	}
	if err := task.SetCompleted(false, now.Add(2*time.Second)); err != nil {
		t.Fatal(err)
	}
	if task.Status != Doing || task.CompletedAt != nil || task.PreviousStatus != nil {
		t.Fatalf("reopened=%+v", task)
	}
}
func TestQueryAndTitleBoundaries(t *testing.T) {
	q := (Query{Page: -3, Size: 1000, Tag: " "}).Normalize()
	if q.Page != 0 || q.Size != 100 || q.Tag != "" {
		t.Fatalf("query=%+v", q)
	}
	if title, err := ValidateTitle("  中文  "); err != nil || title != "中文" {
		t.Fatalf("title=%q err=%v", title, err)
	}
	if _, err := ValidateTitle(""); err == nil {
		t.Fatal("empty title accepted")
	}
}

func TestTagsKeepOrderAndRejectLimits(t *testing.T) {
	tags, err := NormalizeTags([]string{" 工作 ", "工作", "", "学习"})
	if err != nil || len(tags) != 2 || tags[0] != "工作" || tags[1] != "学习" {
		t.Fatalf("tags=%v err=%v", tags, err)
	}
	if _, err := NormalizeTags([]string{"a", "b", "c", "d", "e", "f"}); err == nil {
		t.Fatal("six tags accepted")
	}
	if _, err := NormalizeTags([]string{"😀😀😀😀😀😀😀😀😀😀😀"}); err == nil {
		t.Fatal("UTF-16 overlong tag accepted")
	}
}
