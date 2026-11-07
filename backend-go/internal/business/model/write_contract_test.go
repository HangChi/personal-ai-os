package model

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
)

// 使用真实 Service 和 Runtime，只替换持久层；不访问模型端点。
type writeContractStore struct {
	*readFixture
	profile              Profile
	writeErr, requireErr error
	writes, requires     int
	ids                  []string
	name                 string
	command              SaveCommand
}

func (s *writeContractStore) Save(_ context.Context, c SaveCommand) (Values, error) {
	s.writes++
	s.command = c
	return s.current, s.writeErr
}
func (s *writeContractStore) Create(_ context.Context, c ProfileCommand) (Profile, error) {
	s.writes++
	return s.profile, s.writeErr
}
func (s *writeContractStore) Update(_ context.Context, id string, c ProfileCommand) (Profile, error) {
	s.writes++
	return s.profile, s.writeErr
}
func (s *writeContractStore) Activate(_ context.Context, id string) (Profile, error) {
	s.writes++
	return s.profile, s.writeErr
}
func (s *writeContractStore) Require(_ context.Context, id string) (Profile, error) {
	s.requires++
	return s.profile, s.requireErr
}
func (s *writeContractStore) Rename(_ context.Context, id, name string) (Profile, error) {
	s.writes++
	s.name = name
	return s.profile, s.writeErr
}
func (s *writeContractStore) Delete(_ context.Context, id string) error {
	s.writes++
	return s.writeErr
}
func (s *writeContractStore) Reorder(_ context.Context, ids []string) error {
	s.writes++
	s.ids = ids
	return s.writeErr
}

func modelWriteFixture(t *testing.T, runtime *Runtime) (*Service, *writeContractStore) {
	t.Helper()
	v := Values{APIKey: ptr("synthetic-contract-key"), BaseURL: ptr("https://fixture.invalid/v1"), ChatModel: ptr("fixture-model"), Revision: ptr(3)}
	p := Profile{ID: "fixture", Name: "测试配置", Values: v}
	store := &writeContractStore{readFixture: &readFixture{current: v, legacy: v, profiles: []Profile{p}}, profile: p}
	service, err := NewService(store, resolver(t, nil, nil), runtime)
	if err != nil {
		t.Fatal(err)
	}
	return service, store
}
func validProfileWrite() ProfileCommand { return ProfileCommand{Name: ptr("配置")} }

func TestModelWriteServicePersistsAndAppliesRuntime(t *testing.T) {
	ctx := context.Background()
	runtime := NewRuntime(nil)
	service, store := modelWriteFixture(t, runtime)
	dto, err := service.Save(ctx, SaveCommand{APIKey: nil})
	if err != nil || !dto.HasAPIKey || dto.ChatModel != "fixture-model" || !runtime.Configured() || store.writes != 1 || store.command.APIKey != nil {
		t.Fatal(dto, err, store.writes)
	}
	first := runtime.Current()
	store.current.ChatModel = ptr("replacement")
	store.profile.Active = true
	store.profile.ChatModel = ptr("replacement")
	p, err := service.Update(ctx, "fixture", validProfileWrite())
	if err != nil || p.ChatModel != "replacement" || runtime.Current() == first || runtime.Current().Name() != "replacement" || store.requires != 1 {
		t.Fatal(p, err)
	}
	store.current.ChatModel = ptr("activated")
	if _, err = service.Activate(ctx, "fixture"); err != nil || runtime.Current().Name() != "activated" || store.requires != 2 {
		t.Fatal(err)
	}
	named, err := service.ResolveNamed(ctx, "  per-session  ")
	if err != nil || named.Name() != "per-session" || runtime.Current().Name() != "activated" {
		t.Fatal(named, err)
	}
}

func TestModelWriteWithoutRuntimeAndProfileOperations(t *testing.T) {
	service, store := modelWriteFixture(t, nil)
	ctx := context.Background()
	if _, err := service.Save(ctx, SaveCommand{}); err != nil {
		t.Fatal(err)
	}
	if p, err := service.Create(ctx, validProfileWrite()); err != nil || p.ID != "fixture" {
		t.Fatal(p, err)
	}
	if _, err := service.Update(ctx, "fixture", validProfileWrite()); err != nil || store.requires != 1 || store.calls != 0 {
		t.Fatal(err)
	}
	if _, err := service.Activate(ctx, "fixture"); err != nil || store.requires != 2 {
		t.Fatal(err)
	}
	if _, err := service.Rename(ctx, "fixture", "  新名称  "); err != nil || store.name != "新名称" {
		t.Fatal(err, store.name)
	}
	if list, err := service.Reorder(ctx, []string{"b", "a"}); err != nil || len(list.Profiles) != 1 || strings.Join(store.ids, ",") != "b,a" {
		t.Fatal(list, err)
	}
	if err := service.Delete(ctx, "fixture"); err != nil {
		t.Fatal(err)
	}
	if err := service.Startup(ctx); err != nil {
		t.Fatal(err)
	}
	if _, err := NewService(nil, resolver(t, nil, nil), nil); err == nil {
		t.Fatal("nil store accepted")
	}
}

func TestModelWriteRejectsInvalidInputBeforePersistence(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(*ProfileCommand)
	}{
		{"endpoint-length", func(c *ProfileCommand) { c.BaseURL = ptr(strings.Repeat("x", 501)) }},
		{"key-length", func(c *ProfileCommand) { c.APIKey = ptr(strings.Repeat("x", 501)) }},
		{"chat-length", func(c *ProfileCommand) { c.ChatModel = ptr(strings.Repeat("x", 121)) }},
		{"embedding-length", func(c *ProfileCommand) { c.EmbeddingModel = ptr(strings.Repeat("x", 121)) }},
		{"preset-length", func(c *ProfileCommand) { c.PresetID = ptr(strings.Repeat("x", 61)) }},
		{"protocol-length", func(c *ProfileCommand) { c.Protocol = ptr(strings.Repeat("x", 21)) }},
		{"name-missing", func(c *ProfileCommand) { c.Name = nil }},
		{"name-blank", func(c *ProfileCommand) { c.Name = ptr("  ") }},
		{"name-utf16", func(c *ProfileCommand) { c.Name = ptr(strings.Repeat("😀", 31)) }},
		{"temperature-negative", func(c *ProfileCommand) { c.Temperature = ptr(Temperature(-.1)) }},
		{"temperature-high", func(c *ProfileCommand) { c.Temperature = ptr(Temperature(2.1)) }},
		{"endpoint-scheme", func(c *ProfileCommand) { c.BaseURL = ptr("file:///tmp/private") }},
	}
	for i := 0; i < 3; i++ {
		kind := i
		cases = append(cases, struct {
			name   string
			mutate func(*ProfileCommand)
		}{fmt.Sprint("list-limit-", i), func(c *ProfileCommand) {
			lists := []*[]*string{&c.ChatModels, &c.EmbeddingModels, &c.AudioModels}
			*lists[kind] = make([]*string, 501)
		}}, struct {
			name   string
			mutate func(*ProfileCommand)
		}{fmt.Sprint("list-name-", i), func(c *ProfileCommand) {
			lists := []*[]*string{&c.ChatModels, &c.EmbeddingModels, &c.AudioModels}
			*lists[kind] = []*string{nil, ptr(strings.Repeat("x", 121))}
		}})
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			service, store := modelWriteFixture(t, nil)
			c := validProfileWrite()
			tc.mutate(&c)
			if _, err := service.Create(context.Background(), c); err == nil {
				t.Fatal("invalid create accepted")
			}
			if _, err := service.Update(context.Background(), "fixture", c); err == nil {
				t.Fatal("invalid update accepted")
			}
			if store.writes != 0 {
				t.Fatal("invalid input reached persistence")
			}
		})
	}
	service, store := modelWriteFixture(t, nil)
	for _, c := range []SaveCommand{{APIKey: ptr(strings.Repeat("x", 501))}, {Temperature: ptr(Temperature(3))}, {BaseURL: ptr("ftp://fixture.invalid")}} {
		if _, err := service.Save(context.Background(), c); err == nil {
			t.Fatal("invalid save accepted")
		}
	}
	for _, name := range []string{" ", strings.Repeat("😀", 31)} {
		if _, err := service.Rename(context.Background(), "fixture", name); err == nil {
			t.Fatal("invalid rename accepted")
		}
	}
	if store.writes != 0 {
		t.Fatal("validation wrote data")
	}
	c := validProfileWrite()
	c.Name = ptr(strings.Repeat("😀", 30))
	c.ChatModels = []*string{nil, ptr(" "), ptr(strings.Repeat("a", 120))}
	c.Temperature = ptr(Temperature(2))
	c.BaseURL = ptr(" https://fixture.invalid ")
	if _, err := service.Create(context.Background(), c); err != nil {
		t.Fatal("valid boundary rejected", err)
	}
}

func TestModelWritePropagatesPersistenceAndRereadErrors(t *testing.T) {
	ctx := context.Background()
	failure := errors.New("synthetic persistence failure")
	actions := []struct {
		name string
		run  func(*Service) error
	}{
		{"save", func(s *Service) error { _, e := s.Save(ctx, SaveCommand{}); return e }},
		{"create", func(s *Service) error { _, e := s.Create(ctx, validProfileWrite()); return e }},
		{"update", func(s *Service) error { _, e := s.Update(ctx, "fixture", validProfileWrite()); return e }},
		{"activate", func(s *Service) error { _, e := s.Activate(ctx, "fixture"); return e }},
		{"rename", func(s *Service) error { _, e := s.Rename(ctx, "fixture", "new"); return e }},
		{"delete", func(s *Service) error { return s.Delete(ctx, "fixture") }},
		{"reorder", func(s *Service) error { _, e := s.Reorder(ctx, nil); return e }},
	}
	for _, tc := range actions {
		t.Run(tc.name, func(t *testing.T) {
			s, store := modelWriteFixture(t, nil)
			store.writeErr = failure
			if err := tc.run(s); !errors.Is(err, failure) || store.writes != 1 || store.requires != 0 {
				t.Fatal(err, store.writes, store.requires)
			}
		})
	}
	for _, active := range []bool{false, true} {
		s, store := modelWriteFixture(t, nil)
		store.profile.Active = active
		store.requireErr = failure
		if _, err := s.Update(ctx, "fixture", validProfileWrite()); !errors.Is(err, failure) {
			t.Fatal(err)
		}
		if _, err := s.Activate(ctx, "fixture"); !errors.Is(err, failure) {
			t.Fatal(err)
		}
	}
}

func TestModelRuntimeFailureKeepsCommittedConfigurationAndOldClient(t *testing.T) {
	ctx := context.Background()
	r := NewRuntime(nil)
	old := runtimeClient(t, "https://old.invalid", "old")
	r.Replace(old)
	s, store := modelWriteFixture(t, r)
	store.profile.Active = true
	r.factory = func(Resolved) (*ChatClient, error) { return nil, errors.New("synthetic constructor failure") }
	for _, run := range []func() error{func() error { _, e := s.Save(ctx, SaveCommand{}); return e }, func() error { _, e := s.Update(ctx, "fixture", validProfileWrite()); return e }, func() error { _, e := s.Activate(ctx, "fixture"); return e }} {
		before := store.writes
		if err := run(); err == nil || store.writes != before+1 || r.Current() != old {
			t.Fatal("commit/runtime contract violated", err)
		}
	}
	if store.requires != 0 {
		t.Fatal("reread after failed runtime apply")
	}
	store.err = errors.New("synthetic current read failure")
	if err := s.ApplyCurrent(ctx); !errors.Is(err, store.err) {
		t.Fatal(err)
	}
	if _, err := s.ResolveNamed(ctx, "chosen"); !errors.Is(err, store.err) {
		t.Fatal(err)
	}
	if err := s.Startup(ctx); !errors.Is(err, store.err) {
		t.Fatal(err)
	}
	store.err = nil
	store.current.APIKey = ptr(" ")
	if _, err := s.ResolveNamed(ctx, "chosen"); err == nil {
		t.Fatal("named model without credentials accepted")
	}
	if _, err := s.ResolveNamed(ctx, " "); err == nil {
		t.Fatal("empty named model accepted")
	}
}

func TestModelStartupAndWindowLookupDoNotInventCapabilities(t *testing.T) {
	ctx := context.Background()
	r := NewRuntime(nil)
	s, store := modelWriteFixture(t, r)
	store.legacy.Revision = nil
	if err := s.Startup(ctx); err != nil || r.Configured() {
		t.Fatal(err)
	}
	store.legacy.Revision = ptr(1)
	if err := s.Startup(ctx); err != nil || !r.Configured() {
		t.Fatal(err)
	}
	store.profiles = []Profile{{Active: false, Models: []Model{{Kind: Chat, Name: "chosen", ContextWindow: ptr(99999)}}}, {Active: true, Models: []Model{{Kind: Embedding, Name: "chosen", ContextWindow: ptr(99999)}, {Kind: Chat, Name: "chosen", ContextWindow: nil}, {Kind: Chat, Name: "chosen", ContextWindow: ptr(1000)}, {Kind: Chat, Name: "other", ContextWindow: ptr(8192)}, {Kind: Chat, Name: "chosen", ContextWindow: ptr(4096)}}}}
	window := s.LookupWindow(ctx, "chosen")
	if window == nil || *window != 4096 {
		t.Fatal(window)
	}
	*window = 1
	if *s.LookupWindow(ctx, "chosen") != 4096 {
		t.Fatal("caller mutated stored context window")
	}
	if s.LookupWindow(ctx, "missing") != nil {
		t.Fatal("invented window")
	}
	store.err = errors.New("offline")
	if s.LookupWindow(ctx, "chosen") != nil {
		t.Fatal("lookup failure did not fall back")
	}
	if firstModel([]*string{nil, ptr(" "), ptr(" chosen ")}, ptr("fallback")) == nil || *firstModel([]*string{nil, ptr(" chosen ")}, nil) != "chosen" || *firstModel(nil, ptr(" fallback ")) != "fallback" || firstModel(nil, nil) != nil {
		t.Fatal("model fallback mismatch")
	}
}

func TestModelWriteCommandsNeverSerializeCredentials(t *testing.T) {
	secret := "synthetic-must-not-appear"
	for _, command := range []any{SaveCommand{APIKey: &secret}, ProfileCommand{SaveCommand: SaveCommand{APIKey: &secret}}} {
		b, err := json.Marshal(command)
		if err != nil || strings.Contains(string(b), secret) || strings.Contains(fmt.Sprintf("%v %#v", command, command), secret) {
			t.Fatal("credential leaked")
		}
	}
}
