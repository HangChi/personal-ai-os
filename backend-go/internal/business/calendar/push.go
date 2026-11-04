package calendar

import (
	"context"
	"crypto/ecdh"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf16"

	webpush "github.com/SherClockHolmes/webpush-go"
	"personal-ai-os/backend-go/internal/platform/api"
)

type Push struct {
	events  *Events
	enabled bool
	public  string
	options webpush.Options
	client  *http.Client
}

func NewPush(events *Events, enabled bool, public, private, subject string) (*Push, error) {
	push := &Push{events: events, enabled: enabled, public: public}
	if !enabled {
		return push, nil
	}
	publicBytes, pubErr := decodeKey(public)
	privateBytes, privErr := decodeKey(private)
	key, keyErr := ecdh.P256().NewPrivateKey(privateBytes)
	peer := publicKey(publicBytes)
	if pubErr != nil || privErr != nil || keyErr != nil || peer == nil || !key.PublicKey().Equal(peer) ||
		(!strings.HasPrefix(subject, "mailto:") && !strings.HasPrefix(subject, "https://")) {
		return nil, errors.New("启用 Web Push 需要有效的匹配公私钥和 mailto:/https:// 联系地址")
	}
	transport := http.DefaultTransport.(*http.Transport).Clone()
	transport.Proxy = nil
	transport.DialContext = (&net.Dialer{Timeout: 5 * time.Second}).DialContext
	push.client = &http.Client{Transport: transport, Timeout: 10 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	push.options = webpush.Options{HTTPClient: push.client, Subscriber: strings.TrimPrefix(subject, "mailto:"),
		VAPIDPublicKey: public, VAPIDPrivateKey: private, TTL: 3600}
	return push, nil
}

func publicKey(value []byte) *ecdh.PublicKey {
	key, _ := ecdh.P256().NewPublicKey(value)
	return key
}

func decodeKey(value string) ([]byte, error) {
	return base64.RawURLEncoding.DecodeString(strings.TrimRight(value, "="))
}

func (p *Push) Close() {
	if p.client != nil {
		p.client.CloseIdleConnections()
	}
}

func pushEndpoint(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.User != nil || strings.Contains(raw, "#") ||
		(parsed.Port() != "" && parsed.Port() != "443") {
		return false
	}
	host := parsed.Hostname()
	switch host {
	case "fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com":
		return true
	default:
		return strings.HasSuffix(host, ".notify.windows.com")
	}
}

func (p *Push) Subscribe(ctx context.Context, endpoint, key, auth string) (string, error) {
	if !p.enabled {
		return "", api.NewBusinessError(api.BusinessConflict, "服务端尚未启用后台推送")
	}
	if !pushEndpoint(endpoint) {
		return "", validation("不支持的浏览器推送端点")
	}
	rawKey, keyErr := decodeKey(key)
	rawAuth, authErr := decodeKey(auth)
	if keyErr != nil || authErr != nil || len(rawAuth) != 16 || publicKey(rawKey) == nil {
		return "", validation("推送订阅密钥无效")
	}
	var id string
	err := p.events.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `SELECT pg_advisory_xact_lock(346012304, 505)`); err != nil {
			return err
		}
		var allowed bool
		if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM web_push_subscription WHERE endpoint=$1)
			OR (SELECT count(*)<20 FROM web_push_subscription)`, endpoint).Scan(&allowed); err != nil {
			return err
		}
		if !allowed {
			return api.NewBusinessError(api.BusinessConflict, "最多订阅 20 个浏览器，请先取消旧订阅")
		}
		return tx.QueryRowContext(ctx, `INSERT INTO web_push_subscription(id,endpoint,p256dh,auth,created_at)
			VALUES(gen_random_uuid(),$1,$2,$3,now()) ON CONFLICT(endpoint)
			DO UPDATE SET p256dh=excluded.p256dh,auth=excluded.auth RETURNING id::text`, endpoint, key, auth).Scan(&id)
	})
	return id, err
}

func (p *Push) Unsubscribe(ctx context.Context, endpoint string) error {
	return p.events.pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, "DELETE FROM web_push_subscription WHERE endpoint=$1", endpoint)
		return err
	})
}

type pushJob struct {
	subscription, reminder     string
	attempts                   int
	endpoint, key, auth, title string
}

func (p *Push) Dispatch(ctx context.Context, now time.Time) error {
	if !p.enabled {
		return nil
	}
	var jobs []pushJob
	err := p.events.pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, `UPDATE web_push_delivery SET state='FAILED'
			WHERE state='SENDING' AND attempts>=5 AND next_attempt_at<=$1`, now); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, "DELETE FROM web_push_delivery WHERE created_at<$1", now.Add(-30*24*time.Hour)); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO web_push_delivery(subscription_id,reminder_id,next_attempt_at,created_at)
			SELECT s.id,r.id,$1,$1 FROM reminder r CROSS JOIN web_push_subscription s
			WHERE r.status='PENDING' AND r.remind_at<=$1 AND r.remind_at>=s.created_at AND r.remind_at>$2
			  AND NOT EXISTS (SELECT 1 FROM web_push_delivery d WHERE d.subscription_id=s.id AND d.reminder_id=r.id)
			ORDER BY r.remind_at,r.id,s.id LIMIT 100 ON CONFLICT DO NOTHING`, now, now.Add(-24*time.Hour)); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `WITH ready AS (
			SELECT d.subscription_id,d.reminder_id FROM web_push_delivery d JOIN reminder r ON r.id=d.reminder_id
			WHERE d.state IN ('PENDING','SENDING') AND d.next_attempt_at<=$1 AND d.attempts<5
			  AND r.status='PENDING' AND r.remind_at>$2
			ORDER BY d.next_attempt_at,d.subscription_id,d.reminder_id LIMIT 10 FOR UPDATE OF d SKIP LOCKED
		), claimed AS (
			UPDATE web_push_delivery d SET state='SENDING',attempts=attempts+1,next_attempt_at=$3
			FROM ready WHERE d.subscription_id=ready.subscription_id AND d.reminder_id=ready.reminder_id RETURNING d.*
		) SELECT d.subscription_id::text,d.reminder_id::text,d.attempts,s.endpoint,s.p256dh,s.auth,r.title
		FROM claimed d JOIN web_push_subscription s ON s.id=d.subscription_id JOIN reminder r ON r.id=d.reminder_id`,
			now, now.Add(-24*time.Hour), now.Add(3*time.Minute))
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var job pushJob
			if err := rows.Scan(&job.subscription, &job.reminder, &job.attempts, &job.endpoint, &job.key, &job.auth, &job.title); err != nil {
				return err
			}
			jobs = append(jobs, job)
		}
		return rows.Err()
	})
	if err != nil {
		return err
	}
	for _, job := range jobs {
		if err := p.deliver(ctx, job); err != nil {
			return err
		}
	}
	return nil
}

func (p *Push) deliver(ctx context.Context, job pushJob) error {
	state := "PENDING"
	if pushEndpoint(job.endpoint) {
		units := utf16.Encode([]rune(job.title))
		title := string(utf16.Decode(units[:min(len(units), 120)]))
		body, err := json.Marshal(map[string]string{"title": title, "body": "你有一条到点提醒", "tag": job.reminder, "url": "/calendar"})
		if err != nil {
			return err
		}
		response, err := webpush.SendNotificationWithContext(ctx, body, &webpush.Subscription{Endpoint: job.endpoint,
			Keys: webpush.Keys{P256dh: job.key, Auth: job.auth}}, &p.options)
		if err == nil {
			_ = response.Body.Close()
			switch code := response.StatusCode; {
			case code == 404 || code == 410:
				return p.events.pool.WithConn(ctx, func(conn *sql.Conn) error {
					_, err := conn.ExecContext(ctx, `DELETE FROM web_push_subscription WHERE id=$1 AND p256dh=$2 AND auth=$3`, job.subscription, job.key, job.auth)
					return err
				})
			case code >= 200 && code < 300:
				state = "SENT"
			case code != 429 && code < 500:
				state = "FAILED"
			}
		}
	}
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if job.attempts >= 5 && state == "PENDING" {
		state = "FAILED"
	}
	return p.events.pool.WithConn(ctx, func(conn *sql.Conn) error {
		_, err := conn.ExecContext(ctx, `UPDATE web_push_delivery SET state=$1,next_attempt_at=$2
			WHERE subscription_id=$3 AND reminder_id=$4 AND attempts=$5 AND state='SENDING'`,
			state, time.Now().Add(time.Duration(30<<job.attempts)*time.Second), job.subscription, job.reminder, job.attempts)
		return err
	})
}
