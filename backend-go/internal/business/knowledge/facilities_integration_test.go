//go:build integration

package knowledge

import (
	"context"
	"io"
	"log/slog"
	"os"
	"sync/atomic"
	"testing"
	"time"

	amqp "github.com/rabbitmq/amqp091-go"
)

func TestP6RealOpenSearchProjectionHydrationVersionAndDelete(t *testing.T) {
	endpoint := os.Getenv("GO_TEST_KNOWLEDGE_SEARCH_URL")
	if endpoint == "" {
		t.Skip("需要本轮独立OpenSearch")
	}
	s := fixtureStore(t)
	ctx := context.Background()
	index := "go-p6-" + newID()
	g, e := NewOpenSearch(endpoint, index, "", "", 1024, nil)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { _, _, _ = g.request(context.Background(), "DELETE", "", nil) })
	p := &Projection{Store: s, Gateway: g}
	d, e := s.Ingest(ctx, "real.txt", "", []byte("独立搜索投影 PostgreSQL 权威内容"))
	if e != nil {
		t.Fatal(e)
	}
	if e = p.Sync(ctx); e != nil {
		t.Fatal(e)
	}
	r := &Retriever{Store: s, External: g}
	hits, e := r.Candidates(ctx, "PostgreSQL", 10)
	if e != nil || len(hits) != 1 || hits[0].Score != nil {
		t.Fatal(hits, e)
	}
	id := scalar[string](t, s.Pool, "SELECT id::text FROM vector_store LIMIT 1")
	old := scalar[int64](t, s.Pool, "SELECT last_value FROM knowledge_search_revision")
	ch := Chunk{Content: "stale ghost", Vector: make([]float32, 1024)}
	ch.Vector[0] = 1
	if e = g.Project(ctx, id, old-1, &ch); e != nil {
		t.Fatal(e)
	}
	hits, e = r.Candidates(ctx, "PostgreSQL", 10)
	if e != nil || len(hits) != 1 || hits[0].Content == "stale ghost" {
		t.Fatal(hits, e)
	}
	if _, e = s.Delete(ctx, d.ID); e != nil {
		t.Fatal(e)
	}
	if e = p.Sync(ctx); e != nil {
		t.Fatal(e)
	}
	lex, vec, e := g.Search(ctx, "PostgreSQL", ch.Vector, 10)
	if e != nil || len(lex) != 0 || len(vec) != 0 {
		t.Fatal(lex, vec, e)
	}
}

func TestP6RealRabbitDurableConfirmedWakeupsAndReconnect(t *testing.T) {
	uri := os.Getenv("GO_TEST_KNOWLEDGE_RABBIT_URI")
	if uri == "" {
		t.Skip("需要本轮独立RabbitMQ")
	}
	s := fixtureStore(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	stats := NewStats()
	queue := "personalos.knowledge.ready"
	wake := make(chan struct{}, 8)
	var count atomic.Int64
	bus, e := StartSignals(ctx, uri, queue, s, false, map[string]func(){queue: func() {
		count.Add(1)
		select {
		case wake <- struct{}{}:
		default:
		}
	}}, stats, logger)
	if e != nil {
		t.Fatal(e)
	}
	defer bus.Close()
	connection, e := amqp.Dial(uri)
	if e != nil {
		t.Fatal(e)
	}
	defer connection.Close()
	ch, e := connection.Channel()
	if e != nil {
		t.Fatal(e)
	}
	_, e = ch.QueueDeclare(queue, true, false, false, false, amqp.Table{"x-message-ttl": int32(60000), "x-max-length": int32(1000)})
	if e != nil {
		t.Fatal(e)
	}
	if e = ch.Confirm(false); e != nil {
		t.Fatal(e)
	}
	publisher := channelPublisher{ch, ch.NotifyReturn(make(chan amqp.Return, 1))}
	jobs := &Jobs{Store: s}
	job, e := jobs.Submit(ctx, "", "", "real.txt", nil, []byte("Rabbit durable work"))
	if e != nil {
		t.Fatal(e)
	}
	relay := &SignalRelay{Store: s, Publisher: publisher, IndexQueue: queue, Stats: stats}
	if e = relay.Relay(ctx); e != nil {
		t.Fatal(e)
	}
	select {
	case <-wake:
	case <-time.After(8 * time.Second):
		t.Fatal("no confirmed wakeup")
	}
	// Duplicate and malformed notifications never consume or mutate the durable job.
	if e = publisher.Publish(ctx, queue, "broken"); e != nil {
		t.Fatal(e)
	}
	if e = publisher.Publish(ctx, queue, job.ID); e != nil {
		t.Fatal(e)
	}
	select {
	case <-wake:
	case <-time.After(3 * time.Second):
		t.Fatal("duplicate wakeup lost")
	}
	if scalar[string](t, s.Pool, "SELECT status FROM knowledge_index_job WHERE id=$1", job.ID) != "PENDING" || count.Load() != 2 {
		t.Fatal("business state changed", count.Load())
	}
	c, _ := stats.Snapshot()
	if c["work.wakeups|invalid"] != 1 {
		t.Fatal(c)
	}
	// A new consumer finds the same durable queue after the first stops.
	bus.Close()
	bus2, e := StartSignals(ctx, uri, queue, s, false, map[string]func(){queue: func() { wake <- struct{}{} }}, stats, logger)
	if e != nil {
		t.Fatal(e)
	}
	defer bus2.Close()
	if e = publisher.Publish(ctx, queue, job.ID); e != nil {
		t.Fatal(e)
	}
	select {
	case <-wake:
	case <-time.After(8 * time.Second):
		t.Fatal("restart wakeup lost")
	}
}
