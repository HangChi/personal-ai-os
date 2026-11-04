package memory

import (
	"slices"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func TestRenderPinnedRelevanceAndBudget(t *testing.T) {
	now := time.Date(2026, 11, 4, 0, 0, 0, 0, time.UTC)
	settings := config.MemoryProperties{PinnedMaxCount: 1, Ranking: config.MemoryRanking{HalfLifeDays: 30}}
	candidates := []Entry{
		{ID: "recent", Content: "下午开会", UpdatedAt: now},
		{ID: "pin", Content: "使用中文", Pinned: true, UpdatedAt: now.Add(-time.Hour)},
		{ID: "relevant", Content: "周报每周五提交", UpdatedAt: now.Add(-30 * 24 * time.Hour)},
		{ID: "extra-pin", Content: "使用公制单位", Pinned: true, UpdatedAt: now.Add(-60 * 24 * time.Hour)},
	}
	_, ids := render(candidates, "周报", settings, now, nil)
	if want := []string{"pin", "relevant", "recent", "extra-pin"}; !slices.Equal(ids, want) {
		t.Fatalf("order = %v, want %v", ids, want)
	}

	large := strings.Repeat("😀", 1000)
	for i := range candidates {
		candidates[i].Content = large
	}
	block, ids := render(candidates, "", settings, now, nil)
	if len(ids) != 3 || strings.Count(block, "😀") != 3000 {
		t.Fatalf("expected three intact entries, got %v", ids)
	}
}
