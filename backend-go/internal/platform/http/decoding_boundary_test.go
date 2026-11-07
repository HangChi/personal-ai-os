package httpmiddleware

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http/httptest"
	"strings"
	"testing"
)

type failedBody struct{}

func (failedBody) Read([]byte) (int, error) { return 0, errors.New("body read failed") }
func (failedBody) Close() error             { return nil }

func TestJSONScalarCoercionRejectsBrokenAndOverflowValues(t *testing.T) {
	for _, raw := range []string{`"`, `""`, `" null "`, `9223372036854775808`, `1e100`, `not-a-number`} {
		v, e := JSONIntField(map[string]json.RawMessage{"n": []byte(raw)}, "n")
		if raw == `""` || raw == `" null "` {
			if e != nil || v != nil {
				t.Fatal(raw, v, e)
			}
		} else if e == nil {
			t.Fatal(raw, v)
		}
	}
	for _, raw := range []string{`"`, `""`, `"null"`, `1.5`, `9223372036854775808`} {
		v, e := JSONBoolField(map[string]json.RawMessage{"b": []byte(raw)}, "b")
		if raw == `""` || raw == `"null"` {
			if e != nil || v != nil {
				t.Fatal(raw, v, e)
			}
		} else if e == nil {
			t.Fatal(raw, v)
		}
	}
	if _, e := JSONStringField(map[string]json.RawMessage{"s": []byte(`"`)}, "s"); e == nil {
		t.Fatal("broken scalar accepted")
	}
	if !JSONAcceptable("application/json") || JSONAcceptable("text/plain") {
		t.Fatal("media negotiation")
	}
	for _, mode := range []string{"absent", "empty", "read-error"} {
		r := httptest.NewRequest("POST", "/", strings.NewReader(""))
		r.Header.Set("Content-Type", "application/json")
		if mode == "empty" {
			r.ContentLength = -1
		}
		if mode == "read-error" {
			r.ContentLength = -1
			r.Body = failedBody{}
		}
		_, e := DecodeOptionalJSONObject(httptest.NewRecorder(), r, 1024)
		if (e != nil) != (mode == "read-error") {
			t.Fatal(mode, e)
		}
	}
}

func TestMultipartInvalidLimitsAndIOFailuresCleanUp(t *testing.T) {
	if strings.Contains(fmt.Sprint(&UploadFile{}, &MultipartUpload{}), "path") {
		t.Fatal("internal path exposed")
	}
	for _, limits := range []MultipartLimits{{}, {MaxFileBytes: math.MaxInt64, MaxRequestBytes: 1024}, {MaxFileBytes: 1024, MaxRequestBytes: math.MaxInt64}} {
		if _, e := ParseMultipart(httptest.NewRecorder(), httptest.NewRequest("POST", "/", nil), limits); e == nil {
			t.Fatal("unbounded limits accepted")
		}
	}
	for _, mode := range []string{"directory", "read-error", "field-error", "disposition", "cancel"} {
		t.Run(mode, func(t *testing.T) {
			body := "--boundary\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\nvalue\r\n--boundary--\r\n"
			if mode == "disposition" {
				body = "--boundary\r\nContent-Disposition: broken; name=\"\r\n\r\nx\r\n--boundary--\r\n"
			}
			r := httptest.NewRequest("POST", "/", strings.NewReader(body))
			r.Header.Set("Content-Type", "multipart/form-data; boundary=boundary")
			limits := MultipartLimits{MaxFileBytes: 4096, MaxRequestBytes: 8192, TempDirectory: t.TempDir()}
			if mode == "directory" {
				limits.TempDirectory += "/missing"
			}
			if mode == "read-error" {
				r.Body = failedBody{}
				r.ContentLength = -1
			}
			if mode == "field-error" {
				r.Body = io.NopCloser(io.MultiReader(strings.NewReader(strings.TrimSuffix(body, "value\r\n--boundary--\r\n")), failedBody{}))
				r.ContentLength = -1
			}
			if mode == "cancel" {
				ctx, cancel := context.WithCancel(r.Context())
				cancel()
				r = r.WithContext(ctx)
			}
			if _, e := ParseMultipart(httptest.NewRecorder(), r, limits); e == nil {
				t.Fatal("invalid upload accepted")
			}
		})
	}
}
