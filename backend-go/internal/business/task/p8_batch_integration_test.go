//go:build integration

package task

import (
	"context"
	"database/sql/driver"
	"fmt"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

type countedConnector struct {
	driver.Connector
	queries *atomic.Int64
}

func (c countedConnector) Connect(ctx context.Context) (driver.Conn, error) {
	conn, err := c.Connector.Connect(ctx)
	if err != nil {
		return nil, err
	}
	return countedConnection{Conn: conn, queries: c.queries}, nil
}

type countedConnection struct {
	driver.Conn
	queries *atomic.Int64
}

func (c countedConnection) QueryContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Rows, error) {
	c.queries.Add(1)
	return c.Conn.(driver.QueryerContext).QueryContext(ctx, query, args)
}
func (c countedConnection) ExecContext(ctx context.Context, query string, args []driver.NamedValue) (driver.Result, error) {
	c.queries.Add(1)
	return c.Conn.(driver.ExecerContext).ExecContext(ctx, query, args)
}
func (c countedConnection) BeginTx(ctx context.Context, opts driver.TxOptions) (driver.Tx, error) {
	return c.Conn.(driver.ConnBeginTx).BeginTx(ctx, opts)
}
func (c countedConnection) Ping(ctx context.Context) error { return c.Conn.(driver.Pinger).Ping(ctx) }

func TestPostgresP8TaskBatchTagsPaginationAndStats(t *testing.T) {
	ctx := context.Background()
	var queries atomic.Int64
	pool := fixturePoolWith(t, func(connector driver.Connector) driver.Connector {
		return countedConnector{connector, &queries}
	})
	store, _ := NewPostgresStore(pool)
	service, _ := NewService(store)
	prefix := fmt.Sprintf("p8-batch-%d-", time.Now().UnixNano())
	var ids []string
	for i := range 25 {
		r, err := service.Create(ctx, CreateRequest{Title: fmt.Sprintf("%s%02d", prefix, i), Tags: []string{"first", "second"}})
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, r.ID)
		t.Cleanup(func() { _ = service.Delete(context.Background(), r.ID) })
	}
	queries.Store(0)
	page, err := service.Search(ctx, Query{Keyword: prefix, Size: 20})
	if err != nil || page.Total != 25 || len(page.Items) != 20 || queries.Load() != 3 {
		t.Fatalf("batch page: total=%d items=%d queries=%d err=%v", page.Total, len(page.Items), queries.Load(), err)
	}
	for _, r := range page.Items {
		if !reflect.DeepEqual(r.Tags, []string{"first", "second"}) {
			t.Fatal("tag order", r.Tags)
		}
	}
	var paged []string
	for index := 0; index < 7; index++ {
		p, err := service.Search(ctx, Query{Keyword: prefix, Size: 4, Page: index})
		if err != nil || p.Total != 25 {
			t.Fatal("page total", err)
		}
		for _, r := range p.Items {
			paged = append(paged, r.ID)
		}
	}
	all, err := service.Search(ctx, Query{Keyword: prefix, Size: 100})
	if err != nil {
		t.Fatal(err)
	}
	var ordered []string
	seen := map[string]bool{}
	for _, r := range all.Items {
		ordered = append(ordered, r.ID)
		seen[r.ID] = true
	}
	if len(seen) != 25 || !reflect.DeepEqual(paged, ordered) {
		t.Fatal("pages duplicate, omit or reorder tasks")
	}
	queries.Store(0)
	huge, err := service.Search(ctx, Query{Keyword: prefix, Size: 100, Page: 21474837})
	if err != nil || huge.Total != 25 || len(huge.Items) != 0 || queries.Load() != 1 {
		t.Fatal("huge page", err)
	}
	queries.Store(0)
	byIDs, err := service.FindByIDs(ctx, ids)
	if err != nil || len(byIDs) != 25 || queries.Load() != 2 {
		t.Fatal("full batch still does N+1", queries.Load(), err)
	}
	before, err := service.Stats(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := service.SetCompleted(ctx, ids[0], true); err != nil {
		t.Fatal(err)
	}
	after, err := service.Stats(ctx)
	if err != nil || after.Pending != before.Pending-1 {
		t.Fatal("stats not global", before, after, err)
	}
}
