//go:build integration

package calendar

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
)

func calendarFixturePool(t *testing.T) *database.Pool {
	t.Helper()
	if os.Getenv("GO_TEST_POSTGRES") != "disposable" || os.Getenv("GO_TEST_FLYWAY") != "disposable" {
		t.Skip("需要执行真实 Flyway 的独立临时库")
	}
	o := database.DefaultPostgresOptions()
	o.Host, o.Database = os.Getenv("GO_TEST_POSTGRES_HOST"), os.Getenv("GO_TEST_POSTGRES_DB")
	o.Username, o.Password = os.Getenv("GO_TEST_POSTGRES_USER"), os.Getenv("GO_TEST_POSTGRES_PASSWORD")
	port, err := strconv.Atoi(os.Getenv("GO_TEST_POSTGRES_PORT"))
	if err != nil || o.Host != "127.0.0.1" || o.Database != "personalos_go_test" || o.Username != "go_fixture" {
		t.Fatal("仅允许独立临时库")
	}
	o.Port, o.SSLMode, o.RootCert = port, os.Getenv("GO_TEST_POSTGRES_SSL_MODE"), os.Getenv("GO_TEST_POSTGRES_ROOT_CERT")
	connector, err := database.NewPostgresConnector(o)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = pool.Close() })
	return pool
}

type pushHTTPFunc func(*http.Request) (*http.Response, error)

func (fn pushHTTPFunc) Do(request *http.Request) (*http.Response, error) { return fn(request) }

func TestP5WebPushRecoveryAndRetryClassification(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	pool, replica := calendarFixturePool(t), calendarFixturePool(t)
	settings, _ := config.BindCalendarProperties(nil)
	key, err := ecdh.P256().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	encode := base64.RawURLEncoding.EncodeToString
	public, private := encode(key.PublicKey().Bytes()), encode(key.Bytes())
	first, err := NewPush(NewEvents(pool, settings, time.UTC), true, public, private, "mailto:fixture@example.com")
	if err != nil {
		t.Fatal(err)
	}
	defer first.Close()
	next, err := NewPush(NewEvents(replica, settings, time.UTC), true, public, private, "mailto:fixture@example.com")
	if err != nil {
		t.Fatal(err)
	}
	defer next.Close()
	endpoint := "https://fcm.googleapis.com/fixture"
	auth := encode(make([]byte, 16))
	id, err := first.Subscribe(ctx, endpoint, public, auth)
	if err != nil {
		t.Fatal(err)
	}
	defer first.Unsubscribe(ctx, endpoint)
	if duplicate, err := next.Subscribe(ctx, endpoint, public, auth); err != nil || duplicate != id {
		t.Fatalf("subscription upsert = %q, %v", duplicate, err)
	}
	now := time.Now().UTC().Truncate(time.Microsecond).Add(time.Minute)
	title := "P5 push job"
	lead := 0
	event, err := first.events.Create(ctx, EventRequest{Title: &title, StartsAt: &now, EndsAt: &now, Lead: &lead})
	if err != nil {
		t.Fatal(err)
	}
	defer first.events.Delete(ctx, event.ID)
	if _, err := first.events.Scan(ctx, now); err != nil {
		t.Fatal(err)
	}
	due, err := first.events.Reminders(ctx, "", true, 10, now)
	if err != nil || len(due) != 1 {
		t.Fatalf("due = %+v, %v", due, err)
	}
	exec := func(query string, args ...any) {
		t.Helper()
		err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			_, err := conn.ExecContext(ctx, query, args...)
			return err
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	state := func(want string, attempts int) {
		t.Helper()
		var got string
		var tries int
		err := pool.WithConn(ctx, func(conn *sql.Conn) error {
			return conn.QueryRowContext(ctx, `SELECT state,attempts FROM web_push_delivery WHERE reminder_id=$1`, due[0].ID).Scan(&got, &tries)
		})
		if got != want || tries != attempts || err != nil {
			t.Fatalf("state=%q attempts=%d err=%v, want %q/%d", got, tries, err, want, attempts)
		}
	}
	reply := func(code int) *http.Response {
		return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader(""))}
	}
	next.options.HTTPClient = pushHTTPFunc(func(request *http.Request) (*http.Response, error) {
		if request.Header.Get("Content-Encoding") != "aes128gcm" || !strings.HasPrefix(request.Header.Get("Authorization"), "vapid ") ||
			request.Header.Get("TTL") != "3600" || request.ContentLength < 50 {
			t.Error("unexpected encrypted Push request metadata")
		}
		return reply(http.StatusNoContent), nil
	})
	first.options.HTTPClient = pushHTTPFunc(func(_ *http.Request) (*http.Response, error) {
		state("SENDING", 1)
		// Recover an expired lease using a different pool before the old HTTP attempt returns.
		if err := next.Dispatch(ctx, now.Add(181*time.Second)); err != nil {
			t.Fatal(err)
		}
		state("SENT", 2)
		return reply(http.StatusTooManyRequests), nil
	})
	if err := first.Dispatch(ctx, now); err != nil {
		t.Fatal(err)
	}
	state("SENT", 2)
	next.options.HTTPClient = pushHTTPFunc(func(_ *http.Request) (*http.Response, error) {
		t.Error("sent job was re-enqueued")
		return reply(500), nil
	})
	if err := next.Dispatch(ctx, now.Add(4*time.Minute)); err != nil {
		t.Fatal(err)
	}
	exec("UPDATE web_push_delivery SET state='PENDING',attempts=4,next_attempt_at=$1", now)
	next.options.HTTPClient = pushHTTPFunc(func(_ *http.Request) (*http.Response, error) { return reply(500), nil })
	if err := next.Dispatch(ctx, now); err != nil {
		t.Fatal(err)
	}
	state("FAILED", 5)
	exec("UPDATE web_push_delivery SET state='PENDING',attempts=0,next_attempt_at=$1", now)
	next.options.HTTPClient = pushHTTPFunc(func(_ *http.Request) (*http.Response, error) { return reply(410), nil })
	if err := next.Dispatch(ctx, now); err != nil {
		t.Fatal(err)
	}
	err = pool.WithConn(ctx, func(conn *sql.Conn) error {
		var count int
		if err := conn.QueryRowContext(ctx, `SELECT count(*) FROM web_push_delivery WHERE reminder_id=$1`, due[0].ID).Scan(&count); err != nil {
			return err
		}
		if count != 0 {
			t.Fatal("expired subscription did not cascade to deliveries")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
}
