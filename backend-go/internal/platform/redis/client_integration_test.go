//go:build integration

package redisclient

import (
	"context"
	"crypto/x509"
	"errors"
	"net/url"
	"os"
	"strings"
	"testing"

	"github.com/redis/go-redis/v9"
)

func dedicatedRedisFixture(t *testing.T) Config {
	t.Helper()
	if os.Getenv("GO_TEST_REDIS") != "disposable" {
		t.Skip("仅运行专用临时 Redis fixture")
	}
	return Config{URI: os.Getenv("GO_TEST_REDIS_URI"), RootCertPEM: []byte(os.Getenv("GO_TEST_REDIS_CA_PEM"))}
}

func fixtureClient(t *testing.T, config Config) *Client {
	t.Helper()
	client, err := New(config)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	return client
}

func TestRedisDedicatedAuthenticationAndEval(t *testing.T) {
	config := dedicatedRedisFixture(t)
	client := fixtureClient(t, config)
	ctx := context.Background()
	if err := client.Ping(ctx); err != nil {
		t.Fatal(err)
	}
	value, err := client.Eval(ctx, `return false`, nil)
	if value != nil || err != nil {
		t.Fatal("Lua nil must not be an error", err)
	}
	value, err = client.Eval(ctx, `return redis.call('INCR',KEYS[1])`, []string{"fixture:" + t.Name()})
	if value != int64(1) || err != nil {
		t.Fatal("authenticated Eval failed", err)
	}
	uri, _ := url.Parse(config.URI)
	uri.User = url.UserPassword("", "wrong-fixture-password")
	config.URI = uri.String()
	bad := fixtureClient(t, config)
	if err := bad.Ping(ctx); err == nil || strings.Contains(err.Error(), "wrong-fixture-password") {
		t.Fatal("wrong Redis credentials admitted or leaked", err)
	}
}

func TestRedisTLSCertificateAndHostnameVerification(t *testing.T) {
	config := dedicatedRedisFixture(t)
	if !strings.HasPrefix(config.URI, "rediss:") {
		t.Skip("需 --tls 真实 Redis fixture")
	}
	client := fixtureClient(t, config)
	if err := client.Ping(context.Background()); err != nil {
		t.Fatal("trusted CA and hostname did not connect", err)
	}
	withoutCA := config
	withoutCA.RootCertPEM = nil
	untrusted := fixtureClient(t, withoutCA)
	err := untrusted.Ping(context.Background())
	var untrustedCertificate x509.UnknownAuthorityError
	if !errors.As(err, &untrustedCertificate) {
		t.Fatal("unknown CA not rejected as certificate error", err)
	}
	options, err := optionsFor(config)
	if err != nil {
		t.Fatal(err)
	}
	options.TLSConfig.ServerName = "different-fixture.example"
	wrongName := &Client{client: redis.NewClient(options)}
	t.Cleanup(func() {
		if err := wrongName.Close(); err != nil {
			t.Error(err)
		}
	})
	err = wrongName.Ping(context.Background())
	var mismatch x509.HostnameError
	if !errors.As(err, &mismatch) {
		t.Fatal("wrong hostname not rejected", err)
	}
	// 真实 TLS 端口不接受明文，不能被驱动降级成一次成功连接。
	uri, _ := url.Parse(config.URI)
	uri.Scheme = "redis"
	plaintext := fixtureClient(t, Config{URI: uri.String()})
	if err := plaintext.Ping(context.Background()); err == nil {
		t.Fatal("TLS endpoint accepted plaintext downgrade")
	}
}
