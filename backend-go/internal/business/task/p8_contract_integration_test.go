//go:build integration

package task

import (
	"context"
	"database/sql"
	"fmt"
	"reflect"
	"slices"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/business/confirmation"
)

func TestPostgresP8EmptyTaskTagList(t *testing.T) {
	store, _ := NewPostgresStore(fixturePool(t))
	service, _ := NewService(store)
	tags, err := service.ListTags(context.Background())
	if err != nil || tags == nil || len(tags) != 0 {
		t.Fatal("empty disposable database must return [] tags", tags, err)
	}
}

func TestPostgresP8TaskTagsAndLifecycle(t *testing.T) {
	ctx := context.Background()
	store, _ := NewPostgresStore(fixturePool(t))
	service, _ := NewService(store)
	tag := fmt.Sprintf("p8-%x", time.Now().UnixNano())[:19]
	description := "unchanged description"
	due := time.Now().UTC().Add(time.Hour).Truncate(time.Microsecond)
	r, err := service.Create(ctx, CreateRequest{Title: "P8 tags lifecycle", Description: &description, DueAt: &due, Tags: []string{tag, "second"}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = service.Delete(context.Background(), r.ID) })
	assertTags := func(want []string) {
		t.Helper()
		current, err := service.Get(ctx, r.ID)
		if err != nil || !reflect.DeepEqual(current.Tags, want) {
			t.Fatalf("persisted tags=%v want=%v err=%v", current.Tags, want, err)
		}
	}
	assertTags([]string{tag, "second"})
	title := "P8 changed title"
	if _, err := service.Update(ctx, r.ID, UpdateRequest{Title: &title}); err != nil {
		t.Fatal(err)
	}
	current, err := service.Get(ctx, r.ID)
	if err != nil || current.Description == nil || *current.Description != description || current.DueAt == nil || !current.DueAt.Equal(due) {
		t.Fatal("patch changed omitted fields", err)
	}
	assertTags([]string{tag, "second"})
	replacement := []string{"replacement", tag}
	if _, err := service.Update(ctx, r.ID, UpdateRequest{Tags: &replacement}); err != nil {
		t.Fatal(err)
	}
	assertTags(replacement)
	for _, filter := range []struct {
		value string
		count int64
	}{{tag, 1}, {tag[:len(tag)-1], 0}} {
		page, err := service.Search(ctx, Query{Tag: filter.value})
		if err != nil || page.Total != filter.count {
			t.Fatal("tag filter is not exact", page.Total, err)
		}
	}
	tags, err := service.ListTags(ctx)
	if err != nil || !slices.Contains(tags, tag) {
		t.Fatal("distinct tag missing", err)
	}
	count := 0
	for _, value := range tags {
		if value == tag {
			count++
		}
	}
	if count != 1 {
		t.Fatal("distinct tags repeated")
	}
	empty := []string{}
	if _, err := service.Update(ctx, r.ID, UpdateRequest{Tags: &empty}); err != nil {
		t.Fatal(err)
	}
	assertTags(empty)
	for _, original := range []Status{Todo, Doing} {
		if _, err := service.Update(ctx, r.ID, UpdateRequest{Status: &original}); err != nil {
			t.Fatal(err)
		}
		for range 2 {
			if _, err := service.SetCompleted(ctx, r.ID, true); err != nil {
				t.Fatal(err)
			}
		}
		completed, err := service.Get(ctx, r.ID)
		if err != nil || completed.Status != Done || completed.CompletedAt == nil || completed.PreviousStatus == nil || *completed.PreviousStatus != original {
			t.Fatal("completion state not preserved", err)
		}
		if _, err := service.SetCompleted(ctx, r.ID, false); err != nil {
			t.Fatal(err)
		}
		reopened, err := service.Get(ctx, r.ID)
		if err != nil || reopened.Status != original || reopened.CompletedAt != nil || reopened.PreviousStatus != nil {
			t.Fatal("reopen invariant", err)
		}
	}
	if _, err := service.Update(ctx, r.ID, UpdateRequest{Tags: &replacement}); err != nil {
		t.Fatal(err)
	}
	if err := service.Delete(ctx, r.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := service.Get(ctx, r.ID); err != ErrNotFound {
		t.Fatal("deleted task still readable", err)
	}
	var remaining int
	err = store.pool.WithConn(ctx, func(conn *sql.Conn) error {
		return conn.QueryRowContext(ctx, "SELECT count(*) FROM task_tag WHERE task_id=$1::uuid", r.ID).Scan(&remaining)
	})
	if err != nil || remaining != 0 {
		t.Fatal("tag cascade", remaining, err)
	}
}

func TestPostgresP8TaskOrderingAndBulkDelete(t *testing.T) {
	ctx := context.Background()
	store, _ := NewPostgresStore(fixturePool(t))
	service, _ := NewService(store)
	prefix := fmt.Sprintf("p8-order-%d-", time.Now().UnixNano())
	var ids []string
	now := time.Now().UTC().Truncate(time.Microsecond)
	for i := range 5 {
		var due *time.Time
		if i < 4 {
			value := now.Add(time.Duration(4-i) * time.Hour)
			due = &value
		}
		r, err := service.Create(ctx, CreateRequest{Title: fmt.Sprintf("%s%d", prefix, i), DueAt: due})
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, r.ID)
		t.Cleanup(func() { _ = service.Delete(context.Background(), r.ID) })
	}
	if _, err := service.SetCompleted(ctx, ids[3], true); err != nil {
		t.Fatal(err)
	}
	page, err := service.Search(ctx, Query{Keyword: prefix, Size: 100})
	var actual []string
	for _, r := range page.Items {
		actual = append(actual, r.ID)
	}
	if err != nil || !reflect.DeepEqual(actual, []string{ids[2], ids[1], ids[0], ids[4], ids[3]}) {
		t.Fatal("unfinished/due/null order", actual, err)
	}
	invalid := make([]string, 51)
	for i := range invalid {
		invalid[i] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
	}
	if _, err := service.DeleteAll(ctx, invalid); err == nil {
		t.Fatal("bulk limit missing")
	}
	affected, err := service.DeleteAll(ctx, []string{ids[0], ids[0], ids[1], "00000000-0000-0000-0000-000000000000"})
	if err != nil || affected != 2 {
		t.Fatal("bulk affected rows", affected, err)
	}
}

func TestPostgresP8TaskConfirmationDefersScheduleAndPriority(t *testing.T) {
	ctx := context.Background()
	pool := fixturePool(t)
	store, _ := NewPostgresStore(pool)
	service, _ := NewService(store)
	handler, _ := NewConfirmationHandler(service)
	registry, _ := confirmation.NewRegistryFor(handler.Actions(), handler)
	confirmationStore, _ := confirmation.NewPostgresStore(pool)
	confirmations, _ := confirmation.NewService(confirmationStore, registry)
	requests, _ := NewConfirmationRequests(service, confirmations)
	r, err := service.Create(ctx, CreateRequest{Title: "P8 deferred writes"})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = service.Delete(context.Background(), r.ID) })
	due := time.Now().UTC().Add(time.Hour).Truncate(time.Microsecond)
	scheduled, err := requests.Schedule(ctx, r.ID, &due, "p8-deferred")
	if err != nil {
		t.Fatal(err)
	}
	priority, err := requests.Priority(ctx, r.ID, High, "p8-deferred")
	if err != nil {
		t.Fatal(err)
	}
	before, err := service.Get(ctx, r.ID)
	if err != nil || before.DueAt != nil || before.Priority != Medium {
		t.Fatal("unconfirmed writes leaked", err)
	}
	for _, pending := range []confirmation.DTO{scheduled, priority} {
		result, err := confirmations.Confirm(ctx, pending.ID, pending.Token)
		if err != nil || !result.Executed || result.AffectedCount != 1 {
			t.Fatal("confirmed write", err)
		}
	}
	after, err := service.Get(ctx, r.ID)
	if err != nil || after.DueAt == nil || !after.DueAt.Equal(due) || after.Priority != High {
		t.Fatal("confirmed values missing", err)
	}
}
