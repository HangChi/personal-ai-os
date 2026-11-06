package knowledge

import (
	"context"
	"encoding/json"
	"net/netip"
	"os"
	"strings"
	"testing"
)

func TestSharedCanonicalPathFixtures(t *testing.T) {
	raw, err := os.ReadFile("../../../../specs/035-safe-automation/fixtures/capture-urls.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures []struct{ Raw, Canonical string }
	if err := json.Unmarshal(raw, &fixtures); err != nil {
		t.Fatal(err)
	}
	for _, fixture := range fixtures {
		t.Run(fixture.Raw, func(t *testing.T) {
			got, err := CanonicalURL(fixture.Raw)
			if err != nil || got != fixture.Canonical {
				t.Fatalf("got %q, %v; want %q", got, err, fixture.Canonical)
			}
			again, err := CanonicalURL(got)
			if err != nil || again != got {
				t.Fatalf("canonicalization is not idempotent: %q, %v", again, err)
			}
		})
	}
}

func TestSharedHiddenHTMLFixtures(t *testing.T) {
	raw, err := os.ReadFile("../../../../specs/035-safe-automation/fixtures/capture-html.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixtures []struct {
		Name, HTML, Title  string
		Contains, Excludes []string
	}
	if err := json.Unmarshal(raw, &fixtures); err != nil {
		t.Fatal(err)
	}
	for _, fixture := range fixtures {
		t.Run(fixture.Name, func(t *testing.T) {
			page, err := ExtractWebPage("https://example.com/", fixture.HTML, true)
			if err != nil || page.Title != fixture.Title {
				t.Fatal(page, err)
			}
			for _, text := range fixture.Contains {
				if !strings.Contains(page.Content, text) {
					t.Fatalf("missing %q: %s", text, page.Content)
				}
			}
			for _, text := range fixture.Excludes {
				if strings.Contains(page.Content, text) {
					t.Fatalf("leaked %q: %s", text, page.Content)
				}
			}
		})
	}
}

func TestPublicCaptureURLAndAddress(t *testing.T) {
	got, e := CanonicalURL("HTTPS://Example.COM:443/a/../b#frag")
	if e != nil || got != "https://example.com/b" {
		t.Fatal(got, e)
	}
	for _, url := range []string{"file:///etc/passwd", "http://localhost/", "https://x.internal/", "http://user:pass@example.com/", "https://example.com:444/", "http://2130706433/", "http://[::1]/", "http://example.com/\n"} {
		if _, e := CanonicalURL(url); e == nil {
			t.Fatal(url)
		}
	}
	for _, address := range []string{"0.0.0.0", "10.0.0.1", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.1.1.1", "255.255.255.255", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:0808:0808::1", "3fff::1", "::ffff:127.0.0.1"} {
		if PublicAddress(netip.MustParseAddr(address)) {
			t.Fatal(address)
		}
	}
	for _, address := range []string{"8.8.8.8", "1.1.1.1", "2606:4700:4700::1111"} {
		if !PublicAddress(netip.MustParseAddr(address)) {
			t.Fatal(address)
		}
	}
	if _, e := (PublicFetcher{}).Fetch(context.Background(), "http://127.0.0.1/"); e == nil {
		t.Fatal("private network accepted")
	}
}
func TestExtractWebPage(t *testing.T) {
	p, e := ExtractWebPage("https://example.com/", `<html><head><title>A &amp; B</title><style>secret-style</style></head><body><h1>标题</h1><script>secret-script()</script><p>Hello &amp; world</p></body></html>`, true)
	if e != nil || p.Title != "A & B" || !strings.Contains(p.Content, "Hello & world") || strings.Contains(p.Content, "secret") {
		t.Fatal(p, e)
	}
	if _, e := ExtractWebPage("https://example.com/", strings.Repeat("a", 1048577), false); e == nil {
		t.Fatal("oversize accepted")
	}
}
