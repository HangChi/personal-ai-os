//go:build integration

package calendar

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	webpush "github.com/SherClockHolmes/webpush-go"
)

// The paired harness owns the subscription; this tests the real transport only.
func TestP8BrowserPushTransport(t *testing.T) {
	path := os.Getenv("GO_TEST_P8_PUSH_FIXTURE")
	if path == "" {
		t.Skip("requires disposable browser subscription")
	}
	info, err := os.Lstat(path)
	if err != nil || !info.Mode().IsRegular() || info.Mode().Perm() != 0600 ||
		!strings.HasPrefix(filepath.Base(filepath.Dir(path)), "personalos-p5-chrome-") {
		t.Fatal("requires private harness-owned fixture")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("cannot read disposable fixture")
	}
	var fixture struct {
		PublicKey    string
		PrivateKey   string
		Subscription webpush.Subscription
		Marker       string
	}
	if json.Unmarshal(raw, &fixture) != nil || !pushEndpoint(fixture.Subscription.Endpoint) ||
		fixture.Marker != "P8 paired go transport fixture" {
		t.Fatal("invalid disposable fixture")
	}
	push, err := NewPush(nil, true, fixture.PublicKey, fixture.PrivateKey, "mailto:fixture@example.com")
	if err != nil {
		t.Fatal("invalid disposable VAPID keys")
	}
	defer push.Close()
	payload, _ := json.Marshal(map[string]string{"title": fixture.Marker, "body": "Paired transport fixture",
		"tag": "p8-go", "url": "/calendar"})
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	response, err := webpush.SendNotificationWithContext(ctx, payload, &fixture.Subscription, &push.options)
	if err != nil {
		t.Fatal("disposable transport request failed (details redacted)")
	}
	defer response.Body.Close()
	fmt.Printf("P8_PUSH_STATUS %d\n", response.StatusCode)
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		t.Fatalf("push platform status %d", response.StatusCode)
	}
}
