//go:build integration

package redisclient_test

import (
	"context"
	"io"
	"net"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	redisclient "personal-ai-os/backend-go/internal/platform/redis"
)

// responseLossProxy 只代理本次临时 Redis；握手/预热后丢掉下一次真实响应。
// 不解析或输出请求/凭据，TLS 场景透明转发密文。
type responseLossProxy struct {
	listener net.Listener
	arm      atomic.Bool
	dropped  chan struct{}
	accepts  atomic.Int32
	mu       sync.Mutex
	conns    []net.Conn
	closed   bool
	workers  sync.WaitGroup
}

func newResponseLossProxy(t *testing.T, target string) *responseLossProxy {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	proxy := &responseLossProxy{listener: listener, dropped: make(chan struct{})}
	proxy.workers.Add(1)
	go func() {
		defer proxy.workers.Done()
		for {
			incoming, err := listener.Accept()
			if err != nil {
				return
			}
			proxy.accepts.Add(1)
			proxy.workers.Add(1)
			go func() {
				defer proxy.workers.Done()
				defer incoming.Close()
				outgoing, err := net.DialTimeout("tcp", target, time.Second)
				if err != nil {
					return
				}
				defer outgoing.Close()
				proxy.mu.Lock()
				if proxy.closed {
					proxy.mu.Unlock()
					return
				}
				proxy.conns = append(proxy.conns, incoming, outgoing)
				proxy.mu.Unlock()
				copyDone := make(chan struct{})
				go func() {
					_, _ = io.Copy(outgoing, incoming)
					_ = outgoing.Close()
					close(copyDone)
				}()
				defer func() { _ = incoming.Close(); <-copyDone }()
				buffer := make([]byte, 32*1024)
				for {
					n, err := outgoing.Read(buffer)
					if n > 0 {
						if proxy.arm.CompareAndSwap(true, false) {
							close(proxy.dropped)
							return
						}
						if _, writeErr := incoming.Write(buffer[:n]); writeErr != nil {
							return
						}
					}
					if err != nil {
						return
					}
				}
			}()
		}
	}()
	t.Cleanup(func() {
		_ = listener.Close()
		proxy.mu.Lock()
		proxy.closed = true
		for _, conn := range proxy.conns {
			_ = conn.Close()
		}
		proxy.mu.Unlock()
		proxy.workers.Wait()
	})
	return proxy
}

func TestRedisExecutedWriteWithLostResponseIsNotReplayed(t *testing.T) {
	admin, _ := aclClient(t, "GO_TEST_REDIS_URI")
	uri, err := url.Parse(os.Getenv("GO_TEST_REDIS_URI"))
	if err != nil || uri.Hostname() != "127.0.0.1" {
		t.Fatal("不是回环临时 Redis")
	}
	proxy := newResponseLossProxy(t, uri.Host)
	uri.Host = proxy.listener.Addr().String()
	client, err := redisclient.New(redisclient.Config{URI: uri.String(), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = client.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := client.Ping(ctx); err != nil {
		t.Fatal(err)
	}
	proxy.arm.Store(true)
	key := "fixture:response-loss:counter"
	value, err := client.Eval(ctx, `return redis.call('INCR',KEYS[1])`, []string{key})
	if err == nil || value != nil {
		t.Fatal("丢失响应却被报告为确定成功")
	}
	select {
	case <-proxy.dropped:
	default:
		t.Fatal("未实际注入丢响应")
	}
	count, err := admin.Eval(ctx, `return redis.call('GET',KEYS[1])`, []string{key})
	if err != nil || count != "1" || proxy.accepts.Load() != 1 {
		t.Fatal("已执行写入被重放或结果不确定性未实际产生", err)
	}
	// 明确的新操作允许重新连接；该探测不能重放前次失败的写入。
	if err := client.Ping(ctx); err != nil {
		t.Fatal("故障后新请求无法恢复连接", err)
	}
	count, err = admin.Eval(ctx, `return redis.call('GET',KEYS[1])`, []string{key})
	if err != nil || count != "1" || proxy.accepts.Load() != 2 {
		t.Fatal("重连改变了原操作次数", err)
	}
}
