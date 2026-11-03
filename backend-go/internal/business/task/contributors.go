package task

import (
	"context"
	"strings"
	"time"
)

// ExportContributor and SearchContributor are task-side capabilities. The
// cross-module export and global-search HTTP endpoints are assembled later.
type ExportContributor struct{ Tasks *Service }

func (ExportContributor) Key() string { return "tasks" }
func (c ExportContributor) Batch(ctx context.Context, page, size int) ([]DTO, error) {
	tasks, err := c.Tasks.ExportBatch(ctx, page, size)
	if err != nil {
		return nil, err
	}
	out := make([]DTO, 0, len(tasks))
	now := time.Now()
	for _, task := range tasks {
		out = append(out, task.DTO(now))
	}
	return out, nil
}

type SearchHit struct {
	Kind         string `json:"kind"`
	ID           string `json:"id"`
	Title        string `json:"title"`
	Subtitle     string `json:"subtitle"`
	MatchedField string `json:"matchedField"`
}
type SearchContributor struct{ Tasks *Service }

func (SearchContributor) ModuleName() string { return "任务" }
func (SearchContributor) Kind() string       { return "task" }
func (c SearchContributor) Search(ctx context.Context, keyword string, limit int) ([]SearchHit, error) {
	tasks, err := c.Tasks.SearchHits(ctx, keyword, limit)
	if err != nil {
		return nil, err
	}
	hits := make([]SearchHit, 0, len(tasks))
	for _, t := range tasks {
		status := "待办"
		if t.Status == Doing {
			status = "进行中"
		} else if t.Status == Done {
			status = "已完成"
		}
		priority := "中"
		if t.Priority == High {
			priority = "高"
		} else if t.Priority == Low {
			priority = "低"
		}
		field := "描述"
		if strings.Contains(strings.ToLower(t.Title), strings.ToLower(keyword)) {
			field = "标题"
		}
		hits = append(hits, SearchHit{"task", t.ID, t.Title, status + " · " + priority, field})
	}
	return hits, nil
}
