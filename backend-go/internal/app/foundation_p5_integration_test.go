//go:build integration

package app

import (
	"context"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/calendar"
	"personal-ai-os/backend-go/internal/business/memory"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/config"
)

type memoryEmbedding struct{}

func (memoryEmbedding) Embed(_ context.Context, _ model.EmbeddingRequest) (model.EmbeddingResponse, error) {
	vector := make([]float32, 1024)
	vector[0] = 1
	return model.EmbeddingResponse{Results: []model.EmbeddingResult{{Vector: vector}}}, nil
}

func TestP5MemoryPostgres(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	store, err := memory.NewStore(ctx, f.Pool())
	if err != nil {
		t.Fatal(err)
	}
	store.UseEmbedding(memoryEmbedding{}, "fixture", 0.92)
	first, err := store.Create(ctx, "  每周五用中文提交报告  ")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := store.Pin(ctx, first.ID, true); err != nil {
		t.Fatal(err)
	}
	same, err := store.CreateAuto(ctx, "每周五用中文提交报告")
	if err != nil || same.ID != first.ID || same.Source != "MANUAL" {
		t.Fatalf("duplicate = %+v, %v", same, err)
	}
	second, err := store.CreateAuto(ctx, "周五的报告需要用中文")
	if err != nil || !second.Pinned || second.Source != "AUTO" {
		t.Fatalf("replacement = %+v, %v", second, err)
	}
	settings := config.MemoryProperties{PinnedMaxCount: 10, Ranking: config.MemoryRanking{Enabled: true, Timeout: time.Second}}
	for range 2 {
		block, err := store.Prompt(ctx, "周报", settings)
		if err != nil || !strings.Contains(block, second.Content) || strings.Contains(block, first.Content) {
			t.Fatalf("prompt = %q, %v", block, err)
		}
	}
	all, err := store.List(ctx)
	if err != nil || len(all) != 2 || all[0].ID != second.ID || all[0].HitCount != 1 ||
		all[1].SupersededBy == nil || *all[1].SupersededBy != second.ID {
		t.Fatalf("list = %+v, %v", all, err)
	}
	if err := store.Delete(ctx, second.ID); err != nil {
		t.Fatal(err)
	}
	all, err = store.List(ctx)
	if err != nil || len(all) != 1 || all[0].SupersededBy != nil {
		t.Fatalf("delete = %+v, %v", all, err)
	}
}

func TestP5ReminderPostgres(t *testing.T) {
	f := fixtureFoundation(t, nil, nil)
	ctx := context.Background()
	settings, err := config.BindCalendarProperties(nil)
	if err != nil {
		t.Fatal(err)
	}
	events := calendar.NewEvents(f.Pool(), settings, time.UTC)
	now := time.Date(2026, 11, 4, 1, 0, 0, 0, time.UTC)
	start := now.Add(-time.Hour)
	title := "P5 missed reminder"
	event, err := events.Create(ctx, calendar.EventRequest{Title: &title, StartsAt: &start, EndsAt: &now})
	if err != nil {
		t.Fatal(err)
	}
	for i := range 2 {
		result, err := events.Scan(ctx, now)
		if err != nil || result.Created != int64(1-i) || result.Skipped != int64(i) {
			t.Fatalf("scan %d = %+v, %v", i, result, err)
		}
	}
	items, err := events.Reminders(ctx, "", true, 10, now)
	if err != nil || len(items) != 1 || items[0].SourceID != event.ID {
		t.Fatalf("due = %+v, %v", items, err)
	}
	for _, at := range []time.Time{now, now.Add(time.Minute)} {
		item, err := events.MarkRead(ctx, items[0].ID, at)
		if err != nil || item.Status != "READ" || item.ReadAt == nil || !item.ReadAt.Equal(now) {
			t.Fatalf("read = %+v, %v", item, err)
		}
	}
	if removed, err := events.Delete(ctx, event.ID); err != nil || removed != 1 {
		t.Fatalf("delete removed %d reminders, %v", removed, err)
	}
}
