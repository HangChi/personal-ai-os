package calendar

import (
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"database/sql/driver"
	"encoding/base64"
	"encoding/json"
	"errors"
	webpush "github.com/SherClockHolmes/webpush-go"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

type deliveryTransport func(*http.Request) (*http.Response, error)

func (f deliveryTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func deliveryResponse(code int) *http.Response {
	return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader("")), Header: make(http.Header)}
}

func TestWebhookDeliverySignatureAndRetryContract(t *testing.T) {
	for _, code := range []int{200, 204, 301, 400, 429, 500, 0} {
		for _, attempt := range []int{1, 5} {
			t.Run(http.StatusText(code)+string(rune('0'+attempt)), func(t *testing.T) {
				state := "PENDING"
				if code >= 200 && code < 300 {
					state = "SENT"
				} else if code > 0 && code != 429 && code < 500 {
					state = "FAILED"
				}
				if attempt == 5 && state == "PENDING" {
					state = "FAILED"
				}
				update := execStep("UPDATE reminder_webhook_delivery")
				update.check = func(a []driver.NamedValue) {
					if a[0].Value != state || a[1].Value != int64(30<<attempt) || a[2].Value != "delivery" || a[3].Value != int64(attempt) {
						t.Fatalf("wrong retry receipt: %v", a)
					}
				}
				h, e := NewWebhook(calendarFixture(t, update), "https://example.test/hook", strings.Repeat("s", 32), false)
				if e != nil {
					t.Fatal(e)
				}
				defer h.Close()
				h.client.Transport = deliveryTransport(func(r *http.Request) (*http.Response, error) {
					b, e := io.ReadAll(r.Body)
					if e != nil {
						t.Fatal(e)
					}
					var payload map[string]string
					if json.Unmarshal(b, &payload) != nil || payload["event"] != "reminder.due" || payload["reminderId"] != calendarID || r.Header.Get("Idempotency-Key") != "delivery" {
						t.Fatalf("bad webhook %s", b)
					}
					if r.Header.Get("X-PersonalOS-Signature") != "sha256="+webhookSignature(r.Header.Get("X-PersonalOS-Timestamp"), b, h.secret) {
						t.Fatal("signature mismatch")
					}
					if code == 0 {
						return nil, errors.New("offline")
					}
					return deliveryResponse(code), nil
				})
				e = h.deliver(context.Background(), webhookJob{ID: "delivery", Attempts: attempt, ReminderID: calendarID, Source: "TASK", Title: "提醒", RemindAt: calendarAt()})
				if e != nil {
					t.Fatal(e)
				}
				counts := h.DeliveryCounts()
				if counts[strings.ToLower(state)] != 1 {
					t.Fatal(counts, state)
				}
			})
		}
	}
	if webhookTime(calendarAt()) != "2026-11-07 10:00:00.0" || !strings.HasSuffix(webhookTime(calendarAt().Add(time.Millisecond)), ".001") {
		t.Fatal("timestamp format")
	}
	for _, address := range []string{"http://example.test", "https://u:p@example.test", "https://example.test/#x", "%"} {
		if h, e := NewWebhook(nil, address, strings.Repeat("s", 32), false); e == nil || h != nil {
			t.Fatal(address)
		}
	}
	if _, e := NewWebhook(nil, "https://example.test", "short", false); e == nil {
		t.Fatal("short secret")
	}
	h, e := NewWebhook(nil, "http://127.0.0.1/hook", strings.Repeat("s", 32), true)
	if e != nil {
		t.Fatal(e)
	}
	h.Close()
}

func TestWebhookDispatchClaimAndCancellationContract(t *testing.T) {
	init := []sqlStep{terminalStep("begin"), execStep("INSERT INTO reminder_webhook_target"), execStep("INSERT INTO reminder_webhook_delivery"), execStep("UPDATE reminder_webhook_delivery"), execStep("DELETE FROM reminder_webhook_delivery"), terminalStep("commit")}
	steps := append(init, queryStep("WITH ready AS", sqlValues("job", 1, calendarID, "TASK", "提醒", calendarAt())), execStep("UPDATE reminder_webhook_delivery"), queryStep("WITH ready AS"))
	h, e := NewWebhook(calendarFixture(t, steps...), "https://example.test/hook", strings.Repeat("s", 32), false)
	if e != nil {
		t.Fatal(e)
	}
	defer h.Close()
	h.client.Transport = deliveryTransport(func(r *http.Request) (*http.Response, error) { return deliveryResponse(204), nil })
	if e = h.Dispatch(context.Background()); e != nil || h.DeliveryCounts()["sent"] != 1 {
		t.Fatal(e, h.DeliveryCounts())
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if e = h.deliver(ctx, webhookJob{}); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	for _, stage := range []string{"claim", "scan", "init"} {
		t.Run(stage, func(t *testing.T) {
			bad := queryStep("WITH ready AS")
			bad.err = errors.New("db")
			if stage == "scan" {
				bad.err = nil
				bad.rows = [][]driver.Value{sqlValues(true)}
			}
			steps := []sqlStep{bad}
			if stage == "init" {
				bad = execStep("INSERT INTO reminder_webhook_target")
				bad.err = errors.New("db")
				steps = []sqlStep{terminalStep("begin"), bad, terminalStep("rollback")}
			}
			h, _ := NewWebhook(calendarFixture(t, steps...), "https://example.test", strings.Repeat("s", 32), false)
			defer h.Close()
			var e error
			if stage == "init" {
				e = h.Dispatch(context.Background())
			} else {
				_, e = h.claim(context.Background())
			}
			if e == nil {
				t.Fatal("database failure lost")
			}
		})
	}
}

func pushFixture(t *testing.T, steps ...sqlStep) (*Push, string, string) {
	t.Helper()
	private, e := ecdh.P256().GenerateKey(rand.Reader)
	if e != nil {
		t.Fatal(e)
	}
	priv, pub, e := webpush.GenerateVAPIDKeys()
	if e != nil {
		t.Fatal(e)
	}
	p, e := NewPush(calendarFixture(t, steps...), true, pub, priv, "mailto:test@example.test")
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(p.Close)
	return p, base64.RawURLEncoding.EncodeToString(private.PublicKey().Bytes()), base64.RawURLEncoding.EncodeToString(make([]byte, 16))
}
func TestPushDeliveryClassificationContract(t *testing.T) {
	for _, code := range []int{201, 400, 404, 410, 429, 503, 0} {
		t.Run(http.StatusText(code), func(t *testing.T) {
			state := "PENDING"
			if code == 201 {
				state = "SENT"
			}
			if code == 400 {
				state = "FAILED"
			}
			update := execStep("UPDATE web_push_delivery")
			update.check = func(a []driver.NamedValue) {
				if a[0].Value != state || a[2].Value != "sub" || a[3].Value != "rem" {
					t.Fatal(a)
				}
			}
			if code == 404 || code == 410 {
				update = execStep("DELETE FROM web_push_subscription")
				update.check = func(a []driver.NamedValue) {
					if a[0].Value != "sub" {
						t.Fatal(a)
					}
				}
			}
			p, key, auth := pushFixture(t, update)
			calls := 0
			p.options.HTTPClient = &http.Client{Transport: deliveryTransport(func(r *http.Request) (*http.Response, error) {
				calls++
				if r.URL.Host != "fcm.googleapis.com" || r.Header.Get("Content-Encoding") != "aes128gcm" {
					t.Fatal(r.URL, r.Header)
				}
				if code == 0 {
					return nil, errors.New("offline")
				}
				return deliveryResponse(code), nil
			})}
			if e := p.deliver(context.Background(), pushJob{subscription: "sub", reminder: "rem", attempts: 1, endpoint: "https://fcm.googleapis.com/send/a", key: key, auth: auth, title: strings.Repeat("提醒😀", 100)}); e != nil || calls != 1 {
				t.Fatal(e, calls)
			}
		})
	}
	for _, attempt := range []int{1, 5} {
		update := execStep("UPDATE web_push_delivery")
		update.check = func(a []driver.NamedValue) {
			want := "PENDING"
			if attempt == 5 {
				want = "FAILED"
			}
			if a[0].Value != want {
				t.Fatal(a)
			}
		}
		p, _, _ := pushFixture(t, update)
		if e := p.deliver(context.Background(), pushJob{endpoint: "http://unsafe.test", attempts: attempt}); e != nil {
			t.Fatal(e)
		}
	}
}
func TestPushSubscriptionAndDispatchContract(t *testing.T) {
	for _, allowed := range []bool{false, true} {
		t.Run(map[bool]string{true: "allowed", false: "quota"}[allowed], func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("SELECT EXISTS", sqlValues(allowed))}
			if allowed {
				steps = append(steps, queryStep("INSERT INTO web_push_subscription", sqlValues("sub")), terminalStep("commit"), execStep("DELETE FROM web_push_subscription"))
			} else {
				steps = append(steps, terminalStep("rollback"))
			}
			p, key, auth := pushFixture(t, steps...)
			id, e := p.Subscribe(context.Background(), "https://web.push.apple.com/a", key, auth)
			if (e == nil) != allowed {
				t.Fatal(id, e)
			}
			if allowed {
				if id != "sub" {
					t.Fatal(id)
				}
				if e = p.Unsubscribe(context.Background(), "https://web.push.apple.com/a"); e != nil {
					t.Fatal(e)
				}
			}
		})
	}
	steps := []sqlStep{terminalStep("begin"), execStep("UPDATE web_push_delivery"), execStep("DELETE FROM web_push_delivery"), execStep("INSERT INTO web_push_delivery"), queryStep("WITH ready AS", sqlValues("sub", "rem", 1, "https://unsafe.test", "key", "auth", "title")), terminalStep("commit"), execStep("UPDATE web_push_delivery")}
	p, _, _ := pushFixture(t, steps...)
	if e := p.Dispatch(context.Background(), calendarAt()); e != nil {
		t.Fatal(e)
	}
	disabled, _ := NewPush(nil, false, "", "", "")
	defer disabled.Close()
	if e := disabled.Dispatch(context.Background(), calendarAt()); e != nil {
		t.Fatal(e)
	}
	if _, e := disabled.Subscribe(context.Background(), "", "", ""); e == nil {
		t.Fatal("disabled write")
	}
	if _, e := NewPush(nil, true, "bad", "bad", "bad"); e == nil {
		t.Fatal("invalid VAPID")
	}
	for _, v := range []string{"a\nb", "bad!", "YQ==", "YQ"} {
		b, e := decodeKey(v)
		if strings.HasPrefix(v, "YQ") {
			if e != nil || string(b) != "a" {
				t.Fatal(v, b, e)
			}
		} else if e == nil {
			t.Fatal(v)
		}
	}
	for _, v := range []string{"https://fcm.googleapis.com", "https://a.notify.windows.com", "https://updates.push.services.mozilla.com:443/a"} {
		if !pushEndpoint(v) {
			t.Fatal(v)
		}
	}
	for _, v := range []string{"http://fcm.googleapis.com", "https://fcm.googleapis.com:444/a", "https://user@fcm.googleapis.com", "https://fcm.googleapis.com/#x", "%"} {
		if pushEndpoint(v) {
			t.Fatal(v)
		}
	}
}
