package knowledge

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
)

type searchBoundaryTransport func(*http.Request) (*http.Response, error)

func (f searchBoundaryTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }
func searchBoundaryResponse(code int, body string) *http.Response {
	return &http.Response{StatusCode: code, Body: io.NopCloser(strings.NewReader(body)), Header: make(http.Header)}
}
func TestOpenSearchRequestProjectionAndRecallFailures(t *testing.T) {
	ctx := context.Background()
	for _, mode := range []string{"network", "head-status", "create-network", "create-status", "mapping", "project-network", "project-status", "recall-network", "recall-status", "recall-json", "recall-id"} {
		t.Run(mode, func(t *testing.T) {
			s, e := NewOpenSearch("https://example.test", "fixture", "synthetic", "synthetic", 2, searchBoundaryTransport(func(r *http.Request) (*http.Response, error) {
				if mode == "network" {
					return nil, errors.New("private")
				}
				if r.Header.Get("Authorization") == "" {
					t.Fatal("auth absent")
				}
				if r.Method == "HEAD" {
					code := 200
					if strings.HasPrefix(mode, "create") {
						code = 404
					}
					if mode == "head-status" {
						code = 503
					}
					return searchBoundaryResponse(code, ""), nil
				}
				if r.Method == "PUT" && r.URL.Path == "/fixture" {
					if mode == "create-network" {
						return nil, errors.New("private")
					}
					return searchBoundaryResponse(503, ""), nil
				}
				if strings.HasSuffix(r.URL.Path, "/_mapping") {
					body := `{"fixture":{"mappings":{"properties":{"embedding":{"dimension":2}}}}}`
					if mode == "mapping" {
						body = "{"
					}
					return searchBoundaryResponse(200, body), nil
				}
				if strings.Contains(mode, "network") {
					return nil, errors.New("private")
				}
				if strings.Contains(mode, "status") {
					return searchBoundaryResponse(503, ""), nil
				}
				body := "{"
				if mode == "recall-id" {
					body = `{"hits":{"hits":[{"_id":"invalid"}]}}`
				}
				return searchBoundaryResponse(200, body), nil
			}))
			if e != nil {
				t.Fatal(e)
			}
			if strings.Contains(fmt.Sprintf("%v", s), "synthetic") {
				t.Fatal("credential leak")
			}
			if strings.HasPrefix(mode, "recall") {
				_, _, e = s.Search(ctx, "query", []float32{1, 0}, 2)
			} else {
				e = s.Project(ctx, contractDocA, 1, nil)
			}
			if e == nil {
				t.Fatal("failure swallowed")
			}
		})
	}
	s, e := NewOpenSearch("https://example.test", "fixture", "", "", 2, nil)
	if e != nil {
		t.Fatal(e)
	}
	if e = s.Project(ctx, "invalid", 0, nil); e == nil {
		t.Fatal("invalid identity")
	}
	if _, _, e = s.request(ctx, "POST", "", make(chan int)); e == nil {
		t.Fatal("unencodable body")
	}
	s.endpoint = "http://bad host"
	if _, _, e = s.request(ctx, "GET", "", nil); e == nil {
		t.Fatal("invalid endpoint")
	}
}
func zipBoundary(t *testing.T, entries [][2]string) []byte {
	t.Helper()
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	for _, entry := range entries {
		w, e := z.Create(entry[0])
		if e != nil {
			t.Fatal(e)
		}
		if _, e = w.Write([]byte(entry[1])); e != nil {
			t.Fatal(e)
		}
	}
	if e := z.Close(); e != nil {
		t.Fatal(e)
	}
	return b.Bytes()
}
func TestDocumentReaderXMLContainersAndMalformedArchives(t *testing.T) {
	ctx := context.Background()
	r := Reader{Properties: testProperties(t)}
	if _, e := r.Prepare(ctx, "no-extension", "", []byte("data")); e == nil {
		t.Fatal("extension")
	}
	c, cancel := context.WithCancel(ctx)
	cancel()
	if _, e := r.Prepare(c, "x.txt", "", []byte("synthetic document")); !errors.Is(e, context.Canceled) {
		t.Fatal(e)
	}
	b := &limitedOutput{max: 2}
	if n, e := b.Write([]byte("ab")); e != nil || n != 2 {
		t.Fatal(n, e)
	}
	if _, e := b.Write([]byte("c")); e == nil {
		t.Fatal("unbounded output")
	}
	for _, ext := range []string{"gif", "webp"} {
		if contentType(ext) != "image/"+ext {
			t.Fatal(ext)
		}
	}
	if extension("nodot") != "" {
		t.Fatal("extension")
	}
	for _, body := range []string{`<w:body/>`, `<w:document/>`, `<w:document><w:body>`, `<w:document><w:body/><w:body/></w:document>`} {
		data := zipBoundary(t, [][2]string{{"[Content_Types].xml", "<Types/>"}, {"word/document.xml", body}})
		if _, e := readDOCX(data); e == nil {
			t.Fatal(body)
		}
	}
	for _, entries := range [][][2]string{{{"[Content_Types].xml", "<Types/>"}}, {{"word/document.xml", "<document/>"}}, {{"[Content_Types].xml", "<Types/>"}, {"word/document.xml", "<document/>"}, {"word/document.xml", "<document/>"}}} {
		if _, e := readDOCX(zipBoundary(t, entries)); e == nil {
			t.Fatal("malformed archive")
		}
	}
	data := docxFixture(t, `<w:sdt><w:sdtContent><w:p><w:r><w:t>first</w:t><w:tab/><w:t>second</w:t><w:br/><w:cr/></w:r></w:p></w:sdtContent></w:sdt><w:tbl><w:tr><w:trPr/><w:tc><w:p><w:r><w:t>cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>`)
	if text, e := readDOCX(data); e != nil || !strings.Contains(text, "cell") {
		t.Fatal(text, e)
	}
	entries := make([][2]string, 10001)
	for i := range entries {
		entries[i] = [2]string{fmt.Sprint(i), ""}
	}
	if _, e := readDOCX(zipBoundary(t, entries)); e == nil {
		t.Fatal("archive entry cap")
	}
}
