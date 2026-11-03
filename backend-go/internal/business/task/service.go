package task

import (
	"context"
	"errors"
	"time"
)

var ErrNotFound = errors.New("任务不存在")

type Page struct {
	Items      []Task `json:"items"`
	Page, Size int
	Total      int64
}
type Stats struct {
	Pending int64 `json:"pending"`
	Overdue int64 `json:"overdue"`
}
type Store interface {
	Insert(context.Context, Task) (Task, error)
	Get(context.Context, string) (Task, bool, error)
	Update(context.Context, Task) (Task, error)
	Delete(context.Context, string) (bool, error)
	Search(context.Context, Query) (Page, error)
	Stats(context.Context, time.Time) (Stats, error)
	ListTags(context.Context) ([]string, error)
	DeleteAll(context.Context, []string) (int, error)
	ExportBatch(context.Context, int, int) ([]Task, error)
	SearchHits(context.Context, string, int) ([]Task, error)
	DueBetween(context.Context, time.Time, time.Time) ([]Task, error)
	ListAll(context.Context) ([]Task, error)
	CountAll(context.Context) (int64, error)
	FindByIDs(context.Context, []string) ([]Task, error)
}
type Service struct {
	store Store
	now   func() time.Time
}

func NewService(store Store) (*Service, error) {
	if store == nil {
		return nil, errors.New("任务仓储不能为空")
	}
	return &Service{store, time.Now}, nil
}
func (s *Service) Create(ctx context.Context, r CreateRequest) (Task, error) {
	title, err := ValidateTitle(r.Title)
	if err != nil {
		return Task{}, err
	}
	description, err := NormalizeDescription(r.Description)
	if err != nil {
		return Task{}, err
	}
	priority := Medium
	if r.Priority != nil {
		priority = *r.Priority
	}
	if priority != Low && priority != Medium && priority != High {
		return Task{}, invalid("任务优先级无效")
	}
	now := s.now().UTC()
	tags, err := NormalizeTags(r.Tags)
	if err != nil {
		return Task{}, err
	}
	return s.store.Insert(ctx, Task{Title: title, Description: description, Status: Todo, Priority: priority, DueAt: r.DueAt, CreatedAt: now, UpdatedAt: now, Tags: tags})
}
func (s *Service) Get(ctx context.Context, id string) (Task, error) {
	r, found, err := s.store.Get(ctx, id)
	if err != nil {
		return Task{}, err
	}
	if !found {
		return Task{}, ErrNotFound
	}
	return r, nil
}
func (s *Service) Update(ctx context.Context, id string, change UpdateRequest) (Task, error) {
	r, err := s.Get(ctx, id)
	if err != nil {
		return Task{}, err
	}
	now := s.now().UTC()
	if change.Title != nil {
		r.Title, err = ValidateTitle(*change.Title)
		if err != nil {
			return Task{}, err
		}
	}
	if change.Description != nil {
		r.Description, err = NormalizeDescription(change.Description)
		if err != nil {
			return Task{}, err
		}
	}
	if change.Priority != nil {
		if *change.Priority != Low && *change.Priority != Medium && *change.Priority != High {
			return Task{}, invalid("任务优先级无效")
		}
		r.Priority = *change.Priority
	}
	if change.Status != nil {
		if err = r.SetStatus(*change.Status, now); err != nil {
			return Task{}, err
		}
	}
	if change.DueAt != nil {
		r.DueAt = change.DueAt
	} else if change.ClearDueAt != nil && *change.ClearDueAt {
		r.DueAt = nil
	}
	if change.Tags != nil {
		r.Tags, err = NormalizeTags(*change.Tags)
		if err != nil {
			return Task{}, err
		}
	}
	r.UpdatedAt = now
	return s.store.Update(ctx, r)
}
func (s *Service) SetCompleted(ctx context.Context, id string, completed bool) (Task, error) {
	r, err := s.Get(ctx, id)
	if err != nil {
		return Task{}, err
	}
	if err = r.SetCompleted(completed, s.now().UTC()); err != nil {
		return Task{}, err
	}
	return s.store.Update(ctx, r)
}
func (s *Service) Delete(ctx context.Context, id string) error {
	deleted, err := s.store.Delete(ctx, id)
	if err != nil {
		return err
	}
	if !deleted {
		return ErrNotFound
	}
	return nil
}
func (s *Service) Search(ctx context.Context, q Query) (Page, error) {
	return s.store.Search(ctx, q.Normalize())
}
func (s *Service) Stats(ctx context.Context) (Stats, error)       { return s.store.Stats(ctx, s.now().UTC()) }
func (s *Service) ListTags(ctx context.Context) ([]string, error) { return s.store.ListTags(ctx) }
func (s *Service) DeleteAll(ctx context.Context, ids []string) (int, error) {
	unique := make([]string, 0, len(ids))
	seen := make(map[string]bool)
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			unique = append(unique, id)
		}
	}
	if len(unique) > 50 {
		return 0, invalid("一次最多处理 50 个任务，请拆分后重试")
	}
	if len(unique) == 0 {
		return 0, nil
	}
	return s.store.DeleteAll(ctx, unique)
}
func (s *Service) Reschedule(ctx context.Context, id string, due *time.Time) (Task, error) {
	r, err := s.Get(ctx, id)
	if err != nil {
		return Task{}, err
	}
	r.DueAt = due
	r.UpdatedAt = s.now().UTC()
	return s.store.Update(ctx, r)
}
func (s *Service) ChangePriority(ctx context.Context, id string, p Priority) (Task, error) {
	if p != Low && p != Medium && p != High {
		return Task{}, invalid("任务优先级无效")
	}
	r, err := s.Get(ctx, id)
	if err != nil {
		return Task{}, err
	}
	r.Priority = p
	r.UpdatedAt = s.now().UTC()
	return s.store.Update(ctx, r)
}
func (s *Service) ExportBatch(ctx context.Context, page, size int) ([]Task, error) {
	if page < 0 || size <= 0 {
		return nil, errors.New("导出分页参数无效")
	}
	return s.store.ExportBatch(ctx, page, size)
}
func (s *Service) SearchHits(ctx context.Context, keyword string, limit int) ([]Task, error) {
	if limit <= 0 {
		return []Task{}, nil
	}
	return s.store.SearchHits(ctx, keyword, limit)
}
func (s *Service) FindDueBetween(ctx context.Context, from, to time.Time) ([]Task, error) {
	return s.store.DueBetween(ctx, from, to)
}
func (s *Service) ListAll(ctx context.Context) ([]Task, error) { return s.store.ListAll(ctx) }
func (s *Service) CountAll(ctx context.Context) (int64, error) { return s.store.CountAll(ctx) }
func (s *Service) FindByIDs(ctx context.Context, ids []string) ([]Task, error) {
	if len(ids) == 0 {
		return []Task{}, nil
	}
	return s.store.FindByIDs(ctx, ids)
}
