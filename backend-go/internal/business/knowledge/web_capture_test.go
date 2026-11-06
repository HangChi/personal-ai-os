package knowledge

import (
	"context"
	"net/netip"
	"strings"
	"testing"
)

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
