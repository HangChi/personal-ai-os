package chat

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
	"time"
)

type s3Transport func(*http.Request) (*http.Response, error)

func (f s3Transport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func s3Fixture(t *testing.T, tr http.RoundTripper) *S3Store {
	p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.storage.s3.enabled": "true", "app.storage.s3.endpoint": "https://fixture.invalid", "app.storage.s3.region": "fixture", "app.storage.s3.access-key": "synthetic-access", "app.storage.s3.secret-key": "synthetic-secret", "app.storage.s3.bucket": "fixture-bucket", "app.storage.s3.namespace": "contract", "app.storage.s3.cleanup": "true"}})
	if e != nil {
		t.Fatal(e)
	}
	v, e := OpenS3(p, tr, 16)
	if e != nil {
		t.Fatal(e)
	}
	s := v.(*S3Store)
	t.Cleanup(func() { _ = s.Close() })
	return s
}
func s3Response(r *http.Request, status int, body string, h http.Header) *http.Response {
	return &http.Response{StatusCode: status, Header: h, Body: io.NopCloser(strings.NewReader(body)), Request: r}
}
func TestS3OwnershipConditionalOperationsContract(t *testing.T) {
	ctx := context.Background()
	id := "10000000-0000-4000-8000-000000000001"
	var s *S3Store
	calls := 0
	s = s3Fixture(t, s3Transport(func(r *http.Request) (*http.Response, error) {
		calls++
		if r.Body != nil {
			_, _ = io.Copy(io.Discard, r.Body)
			_ = r.Body.Close()
		}
		if r.Header.Get("Authorization") == "" {
			t.Fatal("unsigned request")
		}
		h := http.Header{"X-Amz-Meta-Personalos-Owner": []string{s.owner}, "Content-Length": []string{"4"}, "Etag": []string{`"v1"`}}
		if r.Method == "PUT" {
			if r.Header.Get("X-Amz-Meta-Personalos-Owner") != s.owner {
				t.Fatal("owner absent")
			}
			h.Set("Content-Length", "0")
			return s3Response(r, 200, "", h), nil
		}
		if r.Method == "GET" || r.Method == "DELETE" {
			if r.Header.Get("If-Match") != `"v1"` {
				t.Fatal("missing conditional operation")
			}
		}
		body := ""
		if r.Method == "GET" {
			body = "data"
		}
		return s3Response(r, 200, body, h), nil
	}))
	if e := s.Put(ctx, id, []byte("data"), "text/plain"); e != nil {
		t.Fatal(e)
	}
	v, ok, e := s.Read(ctx, id)
	if e != nil || !ok || string(v) != "data" {
		t.Fatal(v, ok, e)
	}
	if e = s.Delete(ctx, id); e != nil {
		t.Fatal(e)
	}
	if calls != 5 {
		t.Fatal(calls)
	}
	if e = s.Put(ctx, id, make([]byte, 17), "text/plain"); e == nil {
		t.Fatal("oversize accepted")
	}
	if e = s.Put(ctx, "bad", nil, "text/plain"); e == nil {
		t.Fatal("bad id")
	}
	raw, _ := json.Marshal(s)
	if strings.Contains(fmt.Sprintf("%v %#v %s", s, s, raw), "synthetic") {
		t.Fatal("secret leak")
	}
}
func TestS3ReadFailureAndOwnerFencesContract(t *testing.T) {
	for _, mode := range []string{"missing", "foreignHead", "largeHead", "foreignGet", "largeGet", "streamOverflow", "headError", "getError", "deleteError"} {
		t.Run(mode, func(t *testing.T) {
			var s *S3Store
			s = s3Fixture(t, s3Transport(func(r *http.Request) (*http.Response, error) {
				if mode == "headError" || mode == "getError" && r.Method == "GET" || mode == "deleteError" && r.Method == "DELETE" {
					return nil, errors.New("synthetic confidential transport cause")
				}
				h := http.Header{"X-Amz-Meta-Personalos-Owner": []string{s.owner}, "Content-Length": []string{"4"}}
				if mode == "missing" {
					return s3Response(r, 404, "", h), nil
				}
				if mode == "foreignHead" || mode == "foreignGet" && r.Method == "GET" {
					h.Set("X-Amz-Meta-Personalos-Owner", "foreign")
				}
				if mode == "largeHead" || mode == "largeGet" && r.Method == "GET" {
					h.Set("Content-Length", "100")
				}
				body := "data"
				if mode == "streamOverflow" && r.Method == "GET" {
					h.Del("Content-Length")
					body = strings.Repeat("x", 17)
				}
				return s3Response(r, 200, body, h), nil
			}))
			id := "10000000-0000-4000-8000-000000000001"
			var e error
			if mode == "deleteError" {
				e = s.Delete(context.Background(), id)
			} else {
				_, ok, err := s.Read(context.Background(), id)
				e = err
				if ok {
					t.Fatal("unsafe read returned data")
				}
			}
			want := mode != "missing" && mode != "foreignHead" && mode != "foreignGet"
			if (e != nil) != want {
				t.Fatal(e)
			}
			if e != nil && strings.Contains(e.Error(), "confidential") {
				t.Fatal(e)
			}
		})
	}
}
func TestS3PruneReferencedAndForeignObjectsContract(t *testing.T) {
	ctx := context.Background()
	old := time.Now().Add(-48 * time.Hour)
	id := "10000000-0000-4000-8000-000000000001"
	ref := "10000000-0000-4000-8000-000000000002"
	var s *S3Store
	s = s3Fixture(t, s3Transport(func(r *http.Request) (*http.Response, error) {
		h := http.Header{"X-Amz-Meta-Personalos-Owner": []string{s.owner}, "Content-Length": []string{"0"}}
		if r.URL.Query().Get("list-type") == "2" {
			body := fmt.Sprintf(`<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated><Contents><Key>%s%s</Key><LastModified>%s</LastModified></Contents><Contents><Key>%s%s</Key><LastModified>%s</LastModified></Contents><Contents><Key>foreign/key</Key></Contents></ListBucketResult>`, s.prefix, id, old.Format(time.RFC3339), s.prefix, ref, old.Format(time.RFC3339))
			h.Set("Content-Type", "application/xml")
			return s3Response(r, 200, body, h), nil
		}
		h.Set("Content-Length", "0")
		return s3Response(r, 200, "", h), nil
	}))
	n, e := s.Prune(ctx, map[string]bool{ref: true}, time.Now())
	if e != nil || n != 1 {
		t.Fatal(n, e)
	}
	s.cleanup = false
	if n, e = s.Prune(ctx, nil, time.Now()); e != nil || n != 0 {
		t.Fatal(n, e)
	}
}
