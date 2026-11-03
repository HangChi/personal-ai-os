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
type Stats struct{ Pending, Overdue int64 }
type Store interface {
	Insert(context.Context, Task) (Task, error)
	Get(context.Context, string) (Task, bool, error)
	Update(context.Context, Task) (Task, error)
	Delete(context.Context, string) (bool, error)
	Search(context.Context, Query) (Page, error)
	Stats(context.Context, time.Time) (Stats, error)
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
		return Task{}, errors.New("任务优先级无效")
	}
	now := s.now().UTC()
	return s.store.Insert(ctx, Task{Title: title, Description: description, Status: Todo, Priority: priority, DueAt: r.DueAt, CreatedAt: now, UpdatedAt: now})
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
			return Task{}, errors.New("任务优先级无效")
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
	} else if change.ClearDueAt {
		r.DueAt = nil
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
func (s *Service) Stats(ctx context.Context) (Stats, error) { return s.store.Stats(ctx, s.now().UTC()) }
