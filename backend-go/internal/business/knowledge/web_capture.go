package knowledge

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"golang.org/x/net/html"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"
)

type WebPage struct{ URL, Title, Content string }
type PublicFetcher struct{}

func CanonicalURL(raw string) (string, error) {
	if len(raw) > 2048 || strings.IndexFunc(raw, func(r rune) bool { return unicode.IsControl(r) || r > 127 }) >= 0 {
		return "", invalid("URL 无效或超过 2048 字符")
	}
	u, e := url.Parse(strings.TrimSpace(raw))
	if e != nil || u == nil {
		return "", invalid("URL 格式不正确")
	}
	u.Scheme = strings.ToLower(u.Scheme)
	host := strings.TrimSuffix(strings.ToLower(u.Hostname()), ".")
	if (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || host == "" || u.Opaque != "" || (u.Port() != "" && !((u.Scheme == "http" && u.Port() == "80") || (u.Scheme == "https" && u.Port() == "443"))) {
		return "", invalid("仅支持无凭据、标准端口的公开 HTTP(S) URL")
	}
	if strings.ContainsAny(host, ":[") || !strings.Contains(host, ".") || host == "localhost" || strings.HasSuffix(host, ".localhost") || strings.HasSuffix(host, ".local") || strings.HasSuffix(host, ".internal") || strings.IndexFunc(host, func(r rune) bool { return r > 127 }) >= 0 {
		return "", invalid("禁止本机或内部地址")
	}
	u.Host = host
	u.Fragment = ""
	u.RawFragment = ""
	if u.Path == "" {
		u.Path = "/"
	}
	path := canonicalPath(u.EscapedPath())
	u.RawPath = path
	u.Path, e = url.PathUnescape(path)
	if e != nil {
		return "", invalid("URL 路径无效")
	}
	return u.String(), nil
}

func canonicalPath(path string) string {
	if path == "" {
		return "/"
	}
	// 仅移除字面点段，保留重复斜杠、百分号编码及末尾斜杠的路径语义。
	parts := strings.Split(path, "/")[1:]
	segments := make([]string, 0, len(parts))
	for i, part := range parts {
		switch part {
		case "..":
			if len(segments) > 0 {
				segments = segments[:len(segments)-1]
			}
		case ".":
		default:
			segments = append(segments, part)
			continue
		}
		if i == len(parts)-1 {
			segments = append(segments, "")
		}
	}
	return "/" + strings.Join(segments, "/")
}

var blockedNetworks = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("10.0.0.0/8"), netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("169.254.0.0/16"), netip.MustParsePrefix("172.16.0.0/12"), netip.MustParsePrefix("192.0.0.0/16"), netip.MustParsePrefix("192.2.0.0/16"), netip.MustParsePrefix("192.88.99.0/24"), netip.MustParsePrefix("192.168.0.0/16"), netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"), netip.MustParsePrefix("224.0.0.0/3"), netip.MustParsePrefix("2001::/23"), netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("2002::/16"), netip.MustParsePrefix("3fff::/20"),
}

func PublicAddress(a netip.Addr) bool {
	if !a.IsValid() || a.Is4In6() || a.Zone() != "" {
		return false
	}
	if a.Is6() && !netip.MustParsePrefix("2000::/3").Contains(a) {
		return false
	}
	for _, p := range blockedNetworks {
		if p.Contains(a) {
			return false
		}
	}
	return true
}

var captureSlots = make(chan struct{}, 4)

func (PublicFetcher) Fetch(ctx context.Context, raw string) (WebPage, error) {
	canonical, e := CanonicalURL(raw)
	if e != nil {
		return WebPage{}, e
	}
	select {
	case captureSlots <- struct{}{}:
		defer func() { <-captureSlots }()
	default:
		return WebPage{}, invalid("采集任务繁忙，请稍后再试")
	}
	ctx, cancel := context.WithTimeout(ctx, 15*time.Second)
	defer cancel()
	u, _ := url.Parse(canonical)
	addresses, e := net.DefaultResolver.LookupNetIP(ctx, "ip", u.Hostname())
	if e != nil || len(addresses) == 0 {
		return WebPage{}, invalid("网页 DNS 解析失败")
	}
	for _, a := range addresses {
		if !PublicAddress(a) {
			return WebPage{}, invalid("禁止访问私网、保留或混合 DNS 地址")
		}
	}
	// 后续连接仅使用已经检查的固定IP，TLS仍以原始主机校验证书。
	transport := &http.Transport{Proxy: nil, DisableCompression: true, DisableKeepAlives: true, TLSHandshakeTimeout: 4 * time.Second, ResponseHeaderTimeout: 5 * time.Second, DialContext: func(c context.Context, network, address string) (net.Conn, error) {
		host, port, e := net.SplitHostPort(address)
		if e != nil || !strings.EqualFold(host, u.Hostname()) {
			return nil, invalid("连接主机与检查地址不一致")
		}
		dialer := net.Dialer{Timeout: 4 * time.Second}
		return dialer.DialContext(c, "tcp", net.JoinHostPort(addresses[0].String(), port))
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 15 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	request, e := http.NewRequestWithContext(ctx, "GET", canonical, nil)
	if e != nil {
		return WebPage{}, invalid("URL 无效")
	}
	request.Header.Set("Accept", "text/html, text/plain")
	request.Header.Set("User-Agent", "Personal-AI-OS-Capture/1.0")
	response, e := client.Do(request)
	if e != nil {
		return WebPage{}, invalid("网页采集失败或超时，请检查公开地址")
	}
	defer response.Body.Close()
	if response.StatusCode != 200 {
		return WebPage{}, invalid("网页响应非200，重定向不会自动跟随")
	}
	kind := strings.ToLower(response.Header.Get("Content-Type"))
	if !(strings.TrimSpace(strings.SplitN(kind, ";", 2)[0]) == "text/html" || strings.TrimSpace(strings.SplitN(kind, ";", 2)[0]) == "text/plain") || strings.Contains(kind, "charset=") && !regexp.MustCompile(`charset="?utf-8"?(;.*)?$`).MatchString(kind) {
		return WebPage{}, invalid("只支持 UTF-8 HTML 或纯文本网页")
	}
	if encoding := response.Header.Get("Content-Encoding"); encoding != "" && !strings.EqualFold(encoding, "identity") {
		return WebPage{}, invalid("不支持压缩响应")
	}
	rawBytes, e := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if e != nil || len(rawBytes) > 1<<20 || !utf8.Valid(rawBytes) {
		return WebPage{}, invalid("网页正文无效、超时或超过1MiB")
	}
	return ExtractWebPage(canonical, string(rawBytes), strings.HasPrefix(kind, "text/html"))
}
func ExtractWebPage(source, raw string, isHTML bool) (WebPage, error) {
	body, title := "", ""
	if isHTML {
		var b, t strings.Builder
		z := html.NewTokenizer(strings.NewReader(raw))
		blocked := 0
		inTitle := false
		for {
			kind := z.Next()
			if kind == html.ErrorToken {
				if z.Err() != io.EOF {
					return WebPage{}, invalid("网页HTML解析失败")
				}
				break
			}
			token := z.Token()
			name := strings.ToLower(token.Data)
			danger := name == "script" || name == "style" || name == "noscript" || name == "iframe" || name == "object" || name == "template"
			switch kind {
			case html.StartTagToken:
				if danger {
					blocked++
				}
				if name == "title" {
					inTitle = true
				}
				if blocked == 0 && (name == "p" || name == "div" || name == "br" || name == "li" || name == "h1" || name == "h2") {
					b.WriteByte('\n')
				}
			case html.EndTagToken:
				if danger && blocked > 0 {
					blocked--
				}
				if name == "title" {
					inTitle = false
				}
				if blocked == 0 && (name == "p" || name == "div" || name == "li" || name == "h1" || name == "h2") {
					b.WriteByte('\n')
				}
			case html.TextToken:
				if blocked == 0 {
					if inTitle {
						t.WriteString(token.Data)
					} else {
						b.WriteString(token.Data)
						b.WriteByte(' ')
					}
				}
			}
		}
		body = b.String()
		title = strings.Join(strings.Fields(t.String()), " ")
	} else {
		body = raw
	}
	body = strings.ReplaceAll(body, "\x00", " ")
	body = regexp.MustCompile(`[\t\v\f ]+`).ReplaceAllString(body, " ")
	body = regexp.MustCompile(` *\n *`).ReplaceAllString(body, "\n")
	body = regexp.MustCompile(`\n{3,}`).ReplaceAllString(body, "\n\n")
	body = strings.TrimSpace(body)
	if body == "" || len(body) > 1<<20 {
		return WebPage{}, invalid("提取正文为空或超过1MiB")
	}
	if title == "" {
		u, _ := url.Parse(source)
		title = u.Hostname()
	}
	runes := []rune(title)
	for len(utf16.Encode(runes)) > 200 {
		runes = runes[:len(runes)-1]
	}
	return WebPage{source, string(runes), body}, nil
}
func ContentHash(content string) string {
	sum := sha256.Sum256([]byte(content))
	return hex.EncodeToString(sum[:])
}
