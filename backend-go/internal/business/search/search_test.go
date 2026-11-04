package search

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"strings"
	"testing"
)

type contributor struct {
	module string
	fail   bool
	n      *int
}

func (c contributor) ModuleName() string { return c.module }
func (contributor) Kind() string         { return "fixture" }
func (c contributor) Search(_ context.Context, k string, n int) ([]Hit, error) {
	*c.n = n
	if c.fail {
		return nil, errors.New("unavailable")
	}
	return []Hit{{ID: k}}, nil
}
func TestP7GlobalSearchContractIsolationAndLimits(t *testing.T) {
	if Pattern(`  100%_\  `) != `%100\%\_\\%` {
		t.Fatal(Pattern(`  100%_\  `))
	}
	n := 0
	s := &Service{Contributors: []Contributor{contributor{"记忆", false, &n}, contributor{"任务", false, &n}, contributor{"日程", true, &n}}}
	r, e := s.Search(context.Background(), " 词 ", 100)
	if e != nil || r.Keyword != "词" || r.TotalHits != 2 || r.Groups[0].Module != "任务" || n != 20 {
		t.Fatal(r, e, n)
	}
	if _, e = s.Search(context.Background(), " ", 5); e == nil {
		t.Fatal("blank accepted")
	}
	if _, e = s.Search(context.Background(), strings.Repeat("😀", 51), 5); e == nil {
		t.Fatal("UTF16 bound lost")
	}
	h := s.Handler(slog.New(slog.NewTextHandler(io.Discard, nil)))
	for _, tc := range []struct {
		url    string
		status int
	}{{"/api/v1/search", 400}, {"/api/v1/search?keyword=k&limit=invalid", 400}, {"/api/v1/search?keyword=k&limit=-1", 200}} {
		w := httptest.NewRecorder()
		h.ServeHTTP(w, httptest.NewRequest("GET", tc.url, nil))
		if w.Code != tc.status {
			t.Fatal(tc, w.Code, w.Body.String())
		}
	}
}
