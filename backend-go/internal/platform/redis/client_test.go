package redisclient

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

func TestExplicitRedisOptionsAndSecrets(t *testing.T) {
	config := Config{URI: "rediss://owner:p%40ss%3Aword@redis.example:6380/3"}
	options, err := optionsFor(config)
	if err != nil {
		t.Fatal(err)
	}
	if options.Addr != "redis.example:6380" || options.Username != "owner" || options.Password != "p@ss:word" || options.DB != 3 {
		t.Fatal("URI not parsed explicitly")
	}
	if options.TLSConfig == nil || options.TLSConfig.InsecureSkipVerify || options.TLSConfig.MinVersion != tls.VersionTLS12 || options.TLSConfig.ServerName != "redis.example" {
		t.Fatal("TLS defaults")
	}
	if options.MaxRetries != -1 || options.PoolSize != 4 || options.MaxActiveConns != 4 || !options.ContextTimeoutEnabled || options.ReadTimeout != 2*time.Second || options.WriteTimeout != 2*time.Second || options.Protocol != 2 || !options.DisableIdentity {
		t.Fatal("bounded client options")
	}
	if options.MaintNotificationsConfig == nil || options.MaintNotificationsConfig.Mode != "disabled" {
		t.Fatal("unexpected maintenance mode")
	}
	for _, raw := range []string{"redis://:secret@localhost/", "redis://secret@localhost", "redis://%20:secret@localhost"} {
		opts, err := optionsFor(Config{URI: raw})
		if err != nil || opts.Username != "" || opts.Password != "secret" || opts.DB != 0 || opts.TLSConfig != nil {
			t.Fatal("password-only syntax", err)
		}
	}
	encoded, err := optionsFor(Config{URI: "redis://user%3Aextra:secret@localhost"})
	if err != nil || encoded.Username != "user" || encoded.Password != "extra:secret" {
		t.Fatal("Java decoded userinfo colon semantics")
	}
	client, err := New(config)
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	for _, value := range []any{config, client} {
		for _, format := range []string{"%v", "%+v", "%#v"} {
			text := fmt.Sprintf(format, value)
			if strings.Contains(text, "p@ss") || strings.Contains(text, "redis.example") || strings.Contains(text, "owner") {
				t.Fatal("credentials/endpoint exposed")
			}
		}
	}
	encodedJSON, _ := json.Marshal(config)
	if strings.Contains(string(encodedJSON), "redis") {
		t.Fatal("configuration JSON exposes endpoint")
	}
}

func TestInvalidRedisConfiguration(t *testing.T) {
	for _, uri := range []string{"", "localhost:6379", "http://localhost", "redis:///0", "redis://localhost?", "redis://localhost/0?x=1", "redis://localhost#", "redis://localhost/#fragment", "redis://localhost/-1", "redis://localhost/2147483648", "redis://localhost/not-db", "redis://localhost:0", "redis://localhost:65536", "redis://bad host", "redis://u:%zz@localhost"} {
		if _, err := New(Config{URI: uri}); err == nil || strings.Contains(err.Error(), uri) && uri != "" {
			t.Fatalf("invalid URI accepted or echoed: %q", uri)
		}
	}
	if _, err := New(Config{URI: "rediss://localhost", RootCertPEM: []byte("invalid")}); err == nil {
		t.Fatal("invalid CA accepted")
	}
	if _, err := New(Config{URI: "redis://localhost", RootCertPEM: []byte("invalid")}); err == nil {
		t.Fatal("CA accepted for plaintext")
	}
}

func TestRedisCancellationTimeoutAndClose(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	stop := make(chan struct{})
	done := make(chan struct{})
	defer func() { close(stop); listener.Close(); <-done }()
	go func() {
		defer close(done)
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		defer conn.Close()
		<-stop
	}()
	client, err := New(Config{URI: "redis://:synthetic-secret@" + listener.Addr().String()})
	if err != nil {
		t.Fatal(err)
	}
	defer client.Close()
	canceled, cancel := context.WithCancel(context.Background())
	cancel()
	if err := client.Ping(canceled); !errors.Is(err, context.Canceled) {
		t.Fatal("pre-canceled request", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()
	start := time.Now()
	if err := client.Ping(ctx); err == nil || strings.Contains(err.Error(), "synthetic-secret") {
		t.Fatal("timeout not sanitized", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("parent deadline ignored")
	}
	if err := client.Close(); err != nil {
		t.Fatal(err)
	}
	if err := client.Close(); err != nil {
		t.Fatal("close not idempotent")
	}
	if err := client.Ping(context.Background()); err == nil {
		t.Fatal("closed client used")
	}
}
