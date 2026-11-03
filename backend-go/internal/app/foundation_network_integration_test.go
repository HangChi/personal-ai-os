//go:build integration

package app

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/database"
	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

// faultProxy 只在专用隔离服务前转发原始TCP；TLS端到端保留，不解密或记录凭据。
// partition关闭既有连接并拒绝新连接，hold丢弃服务端响应但继续转发客户端写入。
type faultProxy struct {
	listener                net.Listener
	target                  string
	mu                      sync.Mutex
	closed, partition, hold bool
	pairs                   map[net.Conn]net.Conn
	accepted                chan struct{}
	dropped                 chan struct{}
	wg                      sync.WaitGroup
}

func newFaultProxy(t *testing.T, target string) *faultProxy {
	t.Helper()
	host, _, err := net.SplitHostPort(target)
	if err != nil || host != "127.0.0.1" {
		t.Fatal("故障代理只允许已验证的独立回环服务")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &faultProxy{listener: listener, target: target, pairs: map[net.Conn]net.Conn{}, accepted: make(chan struct{}), dropped: make(chan struct{}, 1)}
	go p.accept()
	t.Cleanup(p.close)
	return p
}

func (p *faultProxy) port() int { return p.listener.Addr().(*net.TCPAddr).Port }

func (p *faultProxy) accept() {
	defer close(p.accepted)
	for {
		client, err := p.listener.Accept()
		if err != nil {
			return
		}
		p.mu.Lock()
		blocked := p.closed || p.partition
		p.mu.Unlock()
		if blocked {
			client.Close()
			continue
		}
		upstream, err := net.DialTimeout("tcp", p.target, 500*time.Millisecond)
		if err != nil {
			client.Close()
			continue
		}
		p.mu.Lock()
		if p.closed || p.partition {
			p.mu.Unlock()
			client.Close()
			upstream.Close()
			continue
		}
		p.pairs[client] = upstream
		p.wg.Add(1)
		p.mu.Unlock()
		go func() {
			defer p.wg.Done()
			written := make(chan struct{})
			go func() { io.Copy(upstream, client); upstream.Close(); close(written) }()
			buffer := make([]byte, 32*1024)
			for {
				n, err := upstream.Read(buffer)
				if n > 0 {
					p.mu.Lock()
					hold := p.hold
					p.mu.Unlock()
					if hold {
						select {
						case p.dropped <- struct{}{}:
						default:
						}
					} else if _, writeErr := client.Write(buffer[:n]); writeErr != nil {
						break
					}
				}
				if err != nil {
					break
				}
			}
			client.Close()
			upstream.Close()
			<-written
			p.mu.Lock()
			delete(p.pairs, client)
			p.mu.Unlock()
		}()
	}
}

func (p *faultProxy) setPartition(blocked bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.partition = blocked
	if blocked {
		for client, upstream := range p.pairs {
			client.Close()
			upstream.Close()
		}
	}
}

func (p *faultProxy) holdResponses() { p.mu.Lock(); p.hold = true; p.mu.Unlock() }

func (p *faultProxy) close() {
	p.mu.Lock()
	p.closed = true
	p.mu.Unlock()
	p.listener.Close()
	p.setPartition(true)
	<-p.accepted
	p.wg.Wait()
}

func networkFoundation(t *testing.T, postgres database.PostgresOptions, values map[string]string, session, rate *redisclient.Client) *Foundation {
	t.Helper()
	p, err := config.LoadBaselineProperties(config.PropertyOptions{Overrides: values})
	if err != nil {
		t.Fatal(err)
	}
	connector, err := database.NewPostgresConnector(postgres)
	if err != nil {
		t.Fatal(err)
	}
	pool := database.DefaultPoolOptions(false)
	pool.ConnectionTimeout = 500 * time.Millisecond
	f, err := OpenFoundation(context.Background(), p, FoundationOptions{Connector: connector, Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", Pool: &pool, SessionRedis: session, RateRedis: rate, Business: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) })}, slog.New(slog.NewTextHandler(io.Discard, nil)))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := f.Close(ctx); err != nil {
			t.Error(err)
		}
	})
	return f
}

func TestFoundationPostgresTCPPartitionAndRecovery(t *testing.T) {
	admin := fixtureFoundation(t, nil, nil)
	appExec(t, admin, "DELETE FROM runtime_rate_bucket")
	options := appPostgresOptions(t)
	proxy := newFaultProxy(t, net.JoinHostPort(options.Host, strconv.Itoa(options.Port)))
	options.Port = proxy.port()
	values := map[string]string{"spring.profiles.active": "monitoring", "app.model-max-concurrent-requests": "1", "app.rate-limit.capacity": "10"}
	f := networkFoundation(t, options, values, nil, nil)
	peer := fixtureFoundation(t, values, nil)
	lease, err := f.ModelGate().TryAcquire(context.Background())
	if err != nil || lease == nil {
		t.Fatal("准备共享租约失败", err)
	}
	proxy.setPartition(true)
	w := httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"database":"DOWN"`) {
		t.Fatal("断网必须由真实探测报告DOWN", w.Code, w.Body.String())
	}
	w = httptest.NewRecorder()
	f.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
	if w.Code != 503 || strings.Contains(w.Body.String(), "components") {
		t.Fatal("管理健康没有拒绝/脱敏", w.Code)
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	if w.Code != 500 {
		t.Fatal("PG故障不能回退本地预算或返回成功", w.Code)
	}
	if other, err := peer.ModelGate().TryAcquire(context.Background()); err != nil || other != nil {
		t.Fatal("断网没有解除仍有效的共享租约", err)
	}
	proxy.setPartition(false)
	deadline := time.Now().Add(5 * time.Second)
	for {
		w = httptest.NewRecorder()
		f.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
		if w.Code == 200 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("PG网络恢复后没有重新建立健康连接")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if err := lease.Release(context.Background()); err != nil {
		t.Fatal("恢复后原所有者不能释放租约", err)
	}
	other, err := peer.ModelGate().TryAcquire(context.Background())
	if err != nil || other == nil {
		t.Fatal("恢复释放后共享容量未归还", err)
	}
	if err := other.Release(context.Background()); err != nil {
		t.Fatal(err)
	}
	w = httptest.NewRecorder()
	f.Handler().ServeHTTP(w, httptest.NewRequest("POST", "/api/v1/settings/model/test", nil))
	if w.Code != 204 {
		t.Fatal("恢复后共享桶/模型保护不可用", w.Code)
	}
}

func TestPostgresCommitResponseLostIsNotRetried(t *testing.T) {
	admin := fixtureFoundation(t, nil, nil)
	appExec(t, admin, "CREATE TABLE go_commit_fault_marker(id integer PRIMARY KEY)")
	t.Cleanup(func() { appExec(t, admin, "DROP TABLE go_commit_fault_marker") })
	options := appPostgresOptions(t)
	proxy := newFaultProxy(t, net.JoinHostPort(options.Host, strconv.Itoa(options.Port)))
	options.Port = proxy.port()
	connector, err := database.NewPostgresConnector(options)
	if err != nil {
		t.Fatal(err)
	}
	pool, err := database.Open(context.Background(), connector, database.DefaultPoolOptions(false))
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 750*time.Millisecond)
	defer cancel()
	err = pool.WithTx(ctx, sql.TxOptions{}, func(tx *sql.Tx) error {
		if _, err := tx.ExecContext(ctx, "INSERT INTO go_commit_fault_marker VALUES(1)"); err != nil {
			return err
		}
		proxy.holdResponses()
		return nil
	})
	if err == nil || !errors.Is(err, context.DeadlineExceeded) {
		t.Fatal("提交响应丢失不能被报告成功", err)
	}
	select {
	case <-proxy.dropped:
	default:
		t.Fatal("没有实际丢弃服务端提交响应")
	}
	if err := admin.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		var count int
		if err := c.QueryRowContext(context.Background(), "SELECT count(*) FROM go_commit_fault_marker WHERE id=1").Scan(&count); err != nil {
			return err
		}
		if count != 1 {
			return errors.New("真实数据库没有保留恰好一次已提交副作用")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func proxiedRedis(t *testing.T, key string) (*redisclient.Client, *faultProxy) {
	t.Helper()
	_ = fixtureAppRedis(t, key)
	endpoint, err := url.Parse(os.Getenv(key))
	if err != nil {
		t.Fatal("专用测试URI无效")
	}
	proxy := newFaultProxy(t, endpoint.Host)
	endpoint.Host = net.JoinHostPort("127.0.0.1", strconv.Itoa(proxy.port()))
	client, err := redisclient.New(redisclient.Config{URI: endpoint.String(), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { client.Close() })
	return client, proxy
}

func TestFoundationRedisTCPPartitionAndRecovery(t *testing.T) {
	// ACL 固定同一精确键空间；只清理当前独立容器中的测试桶，避免污染后续共享预算断言。
	redisAdmin := fixtureAppRedis(t, "GO_TEST_REDIS_URI")
	rateKey := fmt.Sprintf("personalos:rate:%x", sha256.Sum256([]byte("fixture:app-rate")))
	clearBucket := func() {
		if _, err := redisAdmin.Eval(context.Background(), "return redis.call('DEL', KEYS[1])", []string{rateKey}); err != nil {
			t.Error("清理独立容器测试桶失败", err)
		}
	}
	clearBucket()
	t.Cleanup(clearBucket)
	session, sessionProxy := proxiedRedis(t, "GO_TEST_REDIS_SESSION_URI")
	rate, rateProxy := proxiedRedis(t, "GO_TEST_REDIS_RATE_URI")
	admin := fixtureFoundation(t, nil, nil)
	appExec(t, admin, "DELETE FROM runtime_rate_bucket")
	f := networkFoundation(t, appPostgresOptions(t), map[string]string{
		"spring.profiles.active": "monitoring", "app.auth.enabled": "true", "app.auth.password": "fixture-password",
		"app.shared-session.enabled": "true", "app.shared-session.namespace": "fixture:app-sessions",
		"app.rate-limit.redis.enabled": "true", "app.rate-limit.redis.namespace": "fixture:app-rate", "app.rate-limit.capacity": "100",
	}, session, rate)
	server := httptest.NewServer(f.Handler())
	defer server.Close()
	jar, _ := cookiejar.New(nil)
	client := &http.Client{Jar: jar, Timeout: 5 * time.Second}
	status, _ := appRequest(t, client, "POST", server.URL+"/api/v1/auth/login", "", `{"username":"admin","password":"fixture-password"}`)
	if status != 200 {
		t.Fatal("隔离网络会话准备失败", status)
	}
	rateProxy.setPartition(true)
	status, _ = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/test", "", "")
	if status != 429 {
		t.Fatal("Redis限流网络失败必须拒绝且无回退", status)
	}
	status, payload := appRequest(t, client, "GET", server.URL+"/api/v1/auth/me", "", "")
	if status != 200 || payload["data"].(map[string]any)["username"] != "admin" {
		t.Fatal("限流故障污染专用会话")
	}
	rateProxy.setPartition(false)
	status, _ = appRequest(t, client, "POST", server.URL+"/api/v1/settings/model/test", "", "")
	if status != 204 {
		t.Fatal("网络恢复后Redis预算不可用", status)
	}
	sessionProxy.setPartition(true)
	status, _ = appRequest(t, client, "GET", server.URL+"/api/v1/auth/me", "", "")
	if status != 500 {
		t.Fatal("会话网络故障不能回退匿名/本地会话", status)
	}
	w := httptest.NewRecorder()
	f.ManagementHandler().ServeHTTP(w, httptest.NewRequest("GET", "/actuator/health", nil))
	if w.Code != 503 {
		t.Fatal("会话网络故障未进入管理健康")
	}
	sessionProxy.setPartition(false)
	status, payload = appRequest(t, client, "GET", server.URL+"/api/v1/auth/me", "", "")
	if status != 200 || payload["data"].(map[string]any)["username"] != "admin" {
		t.Fatal("网络恢复后没有读回原Redis会话")
	}
	if err := admin.Pool().WithConn(context.Background(), func(c *sql.Conn) error {
		var count int
		if err := c.QueryRowContext(context.Background(), "SELECT count(*) FROM runtime_rate_bucket").Scan(&count); err != nil {
			return err
		}
		if count != 0 {
			return errors.New("Redis故障期间错误回退到PostgreSQL桶")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}
