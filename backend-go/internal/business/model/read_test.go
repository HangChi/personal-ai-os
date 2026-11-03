package model

import (
	"context"
	"errors"
	"testing"
)

type readFixture struct {
	ReadStore
	legacy, current Values
	profiles        []Profile
	err             error
	calls           int
}

func (s *readFixture) Legacy(context.Context) (Values, bool, error) {
	s.calls++
	return s.legacy, true, s.err
}
func (s *readFixture) Current(context.Context) (Values, error) { s.calls++; return s.current, s.err }
func (s *readFixture) Profiles(context.Context) ([]Profile, error) {
	s.calls++
	return s.profiles, s.err
}

func TestReadServiceSeparatesLegacyAndActiveAndRefreshes(t *testing.T) {
	store := &readFixture{legacy: Values{ChatModel: ptr("legacy")}, current: Values{ChatModel: ptr("active")}}
	service, err := NewReadService(store, resolver(t, nil, nil))
	if err != nil {
		t.Fatal(err)
	}
	dto, err := service.Config(context.Background())
	if err != nil || dto.ChatModel != "legacy" {
		t.Fatal("legacy config mismatch")
	}
	current, err := service.Current(context.Background())
	if err != nil || current.ChatModel != "active" {
		t.Fatal("active config mismatch")
	}
	store.current.ChatModel = ptr("switched")
	current, err = service.Current(context.Background())
	if err != nil || current.ChatModel != "switched" || store.calls != 3 {
		t.Fatal("stale current configuration")
	}
	store.profiles = []Profile{{ID: "first", Active: false}, {ID: "active", Active: true}}
	list, err := service.Profiles(context.Background())
	if err != nil || list.ActiveID == nil || *list.ActiveID != "active" || len(list.Profiles) != 2 {
		t.Fatal("list mismatch")
	}
	store.err = errors.New("synthetic-store-failure")
	if _, err := service.Config(context.Background()); !errors.Is(err, store.err) {
		t.Fatal("legacy error lost")
	}
	if _, err := service.Current(context.Background()); !errors.Is(err, store.err) {
		t.Fatal("current error lost")
	}
	if list, err := service.Profiles(context.Background()); !errors.Is(err, store.err) || len(list.Profiles) != 0 {
		t.Fatal("partial list returned on error")
	}
}

func TestReadConstructorsRequireDependencies(t *testing.T) {
	if _, err := NewResolver(nil); err == nil {
		t.Fatal("nil properties accepted")
	}
	if _, err := NewReadService(nil, resolver(t, nil, nil)); err == nil {
		t.Fatal("nil store accepted")
	}
	if _, err := NewReadService(&readFixture{}, nil); err == nil {
		t.Fatal("nil resolver accepted")
	}
	if _, err := NewPostgresReadStore(nil, fixtureKey(t, 1)); err == nil {
		t.Fatal("nil pool accepted")
	}
}
