package chat

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"personal-ai-os/backend-go/internal/platform/config"
)

func TestS3ConfigurationValidationAndOwnedTransport(t *testing.T) {
	for _, tc := range []struct{ key, value string }{
		{"enabled", "${contract.missing}"}, {"enabled", "maybe"}, {"endpoint", "${contract.missing}"}, {"endpoint", "ftp://example.test"}, {"bucket", "INVALID"}, {"access-key", ""}, {"cleanup", "maybe"},
	} {
		t.Run(tc.key+tc.value, func(t *testing.T) {
			overrides := map[string]string{"app.storage.s3.enabled": "true", "app.storage.s3.endpoint": "https://example.test", "app.storage.s3.region": "test", "app.storage.s3.bucket": "test-bucket", "app.storage.s3.namespace": "test", "app.storage.s3.access-key": "synthetic", "app.storage.s3.secret-key": "synthetic", "app.storage.s3." + tc.key: tc.value}
			p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			if _, e = OpenS3(p, nil, 16); e == nil {
				t.Fatal("invalid configuration accepted")
			}
		})
	}
	// Construct and close the real transport without sending a request.
	s := s3Fixture(t, nil)
	if s.transport == nil {
		t.Fatal("missing owned transport")
	}
	if e := s.Close(); e != nil {
		t.Fatal(e)
	}
}

type s3BrokenBody struct{}

func (s3BrokenBody) Read([]byte) (int, error) { return 0, errors.New("synthetic body failure") }
func (s3BrokenBody) Close() error             { return nil }

func TestS3FailedOperationsPreserveOwnershipAndPruneProgress(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	for _, mode := range []string{"put", "body", "list", "head", "foreign", "delete"} {
		t.Run(mode, func(t *testing.T) {
			var s *S3Store
			s = s3Fixture(t, s3Transport(func(r *http.Request) (*http.Response, error) {
				if r.Body != nil {
					_, _ = io.Copy(io.Discard, r.Body)
					_ = r.Body.Close()
				}
				if mode == "put" || mode == "list" || mode == "head" && r.Method == "HEAD" || mode == "delete" && r.Method == "DELETE" {
					return nil, errors.New("synthetic unavailable")
				}
				h := http.Header{"X-Amz-Meta-Personalos-Owner": []string{s.owner}, "Content-Length": []string{"0"}}
				if r.URL.Query().Get("list-type") == "2" {
					body := fmt.Sprintf(`<ListBucketResult><IsTruncated>false</IsTruncated><Contents><Key>%s%s</Key><LastModified>%s</LastModified></Contents></ListBucketResult>`, s.prefix, id, time.Now().Add(-48*time.Hour).Format(time.RFC3339))
					return s3Response(r, 200, body, h), nil
				}
				if mode == "foreign" {
					h.Set("X-Amz-Meta-Personalos-Owner", "foreign")
				}
				v := s3Response(r, 200, "", h)
				if mode == "body" && r.Method == "GET" {
					v.Body = s3BrokenBody{}
					v.Header.Del("Content-Length")
				}
				return v, nil
			}))
			ctx := context.Background()
			if _, _, e := s.Read(ctx, "invalid"); e == nil {
				t.Fatal("bad read id accepted")
			}
			if e := s.Delete(ctx, "invalid"); e == nil {
				t.Fatal("bad delete id accepted")
			}
			var e error
			switch mode {
			case "put":
				e = s.Put(ctx, id, []byte("a"), "text/plain")
			case "body":
				_, _, e = s.Read(ctx, id)
			default:
				var n int
				n, e = s.Prune(ctx, nil, time.Now())
				if n != 0 {
					t.Fatal(n)
				}
			}
			if (e != nil) != (mode != "foreign") {
				t.Fatal(mode, e)
			}
			if e != nil && strings.Contains(e.Error(), "synthetic") {
				t.Fatal("private cause exposed")
			}
			if mode == "foreign" || mode == "head" {
				if e := s.Delete(ctx, id); (e != nil) != (mode == "head") {
					t.Fatal(e)
				}
			}
		})
	}
}
