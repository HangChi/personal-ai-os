package calendar

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf16"
)

type Webhook struct {
	events   *Events
	endpoint string
	target   string
	secret   []byte
	client   *http.Client
	sent     atomic.Uint64
	pending  atomic.Uint64
	failed   atomic.Uint64
}

func NewWebhook(events *Events, address, secret string, allowLocalHTTP bool) (*Webhook, error) {
	endpoint, err := url.Parse(address)
	if err != nil || endpoint.Hostname() == "" || endpoint.User != nil || strings.Contains(address, "#") || endpoint.Opaque != "" ||
		(endpoint.Scheme != "https" && !(allowLocalHTTP && endpoint.Scheme == "http" && endpoint.Hostname() == "127.0.0.1")) ||
		len(utf16.Encode([]rune(secret))) < 32 {
		return nil, errors.New("提醒 Webhook 配置非法")
	}
	address = endpoint.String()
	hash := sha256.Sum256([]byte(address))
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = (&net.Dialer{Timeout: 3 * time.Second}).DialContext
	client := &http.Client{Transport: transport, Timeout: 5 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	return &Webhook{events: events, endpoint: address, target: hex.EncodeToString(hash[:]), secret: []byte(secret), client: client}, nil
}

func (h *Webhook) Close() {
	h.client.CloseIdleConnections()
}

func (h *Webhook) Dispatch(ctx context.Context) error {
	err := h.events.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "INSERT INTO reminder_webhook_target(target) VALUES($1) ON CONFLICT DO NOTHING", h.target); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO reminder_webhook_delivery(target,reminder_id)
			SELECT t.target,r.id FROM reminder_webhook_target t CROSS JOIN reminder r
			WHERE t.target=$1 AND r.status='PENDING' AND r.remind_at<=now()
			  AND r.remind_at>=t.enabled_at AND r.remind_at>now()-interval '24 hours'
			  AND NOT EXISTS (SELECT 1 FROM reminder_webhook_delivery d WHERE d.target=t.target AND d.reminder_id=r.id)
			ORDER BY r.remind_at,r.id LIMIT 100 ON CONFLICT DO NOTHING`, h.target); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE reminder_webhook_delivery SET state='FAILED'
			WHERE target=$1 AND state='SENDING' AND attempts>=5 AND next_attempt_at<=now()`, h.target); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, "DELETE FROM reminder_webhook_delivery WHERE created_at<now()-interval '30 days'")
		return err
	})
	if err != nil {
		return err
	}
	for range 10 {
		job, err := h.claim(ctx)
		if err != nil || job.ID == "" {
			return err
		}
		if err := h.deliver(ctx, job); err != nil {
			return err
		}
	}
	return nil
}

type webhookJob struct {
	ID         string
	Attempts   int
	ReminderID string
	Source     string
	Title      string
	RemindAt   time.Time
}

func (h *Webhook) claim(ctx context.Context) (webhookJob, error) {
	var job webhookJob
	err := h.events.pool.WithConn(ctx, func(conn *sql.Conn) error {
		err := conn.QueryRowContext(ctx, `WITH ready AS (
			SELECT d.id FROM reminder_webhook_delivery d JOIN reminder r ON r.id=d.reminder_id
			WHERE d.target=$1 AND d.state IN ('PENDING','SENDING') AND d.next_attempt_at<=now()
			  AND d.attempts<5 AND r.status='PENDING' AND r.remind_at>now()-interval '24 hours'
			ORDER BY d.next_attempt_at,d.id FOR UPDATE OF d SKIP LOCKED LIMIT 1
		), claimed AS (
			UPDATE reminder_webhook_delivery d SET state='SENDING',attempts=attempts+1,
				next_attempt_at=now()+interval '60 seconds' FROM ready WHERE d.id=ready.id RETURNING d.*
		)
		SELECT d.id::text,d.attempts,r.id::text,r.source_type,r.title,r.remind_at FROM claimed d JOIN reminder r ON r.id=d.reminder_id`, h.target).
			Scan(&job.ID, &job.Attempts, &job.ReminderID, &job.Source, &job.Title, &job.RemindAt)
		if errors.Is(err, sql.ErrNoRows) {
			return nil
		}
		return err
	})
	return job, err
}

func (h *Webhook) deliver(ctx context.Context, job webhookJob) error {
	body, err := json.Marshal(map[string]string{"event": "reminder.due", "id": job.ID, "reminderId": job.ReminderID,
		"title": job.Title, "sourceType": job.Source, "remindAt": webhookTime(job.RemindAt)})
	if err != nil {
		return err
	}
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, h.endpoint, bytes.NewReader(body))
	if err != nil {
		return errors.New("无法创建提醒 Webhook 请求")
	}
	request.Header.Set("Content-Type", "application/json")
	request.Header.Set("Idempotency-Key", job.ID)
	request.Header.Set("X-PersonalOS-Timestamp", timestamp)
	request.Header.Set("X-PersonalOS-Signature", "sha256="+webhookSignature(timestamp, body, h.secret))
	state := "PENDING"
	response, err := h.client.Do(request)
	if err == nil {
		_ = response.Body.Close()
		code := response.StatusCode
		switch {
		case code >= 200 && code < 300:
			state = "SENT"
		case code != http.StatusTooManyRequests && code < 500:
			state = "FAILED"
		}
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if job.Attempts >= 5 && state == "PENDING" {
		state = "FAILED"
	}
	err = h.events.pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `UPDATE reminder_webhook_delivery SET state=$1,next_attempt_at=now()+$2*interval '1 second'
			WHERE id=$3 AND attempts=$4 AND state='SENDING'`, state, 30<<job.Attempts, job.ID, job.Attempts)
		return err
	})
	if err == nil {
		switch state {
		case "SENT":
			h.sent.Add(1)
		case "PENDING":
			h.pending.Add(1)
		case "FAILED":
			h.failed.Add(1)
		}
	}
	return err
}

func webhookTime(at time.Time) string {
	at = at.UTC()
	layout := "2006-01-02 15:04:05.999999999"
	if at.Nanosecond() == 0 {
		return at.Format(layout) + ".0"
	}
	return at.Format(layout)
}

func webhookSignature(timestamp string, body, secret []byte) string {
	mac := hmac.New(sha256.New, secret)
	_, _ = mac.Write([]byte(timestamp + "."))
	_, _ = mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

func (h *Webhook) DeliveryCounts() map[string]uint64 {
	return map[string]uint64{"sent": h.sent.Load(), "pending": h.pending.Load(), "failed": h.failed.Load()}
}
