package search

import (
	"context"
	"log/slog"
	"net/http"
	"personal-ai-os/backend-go/internal/platform/api"
	web "personal-ai-os/backend-go/internal/platform/http"
	"sort"
	"strconv"
	"strings"
	"unicode/utf16"
)

type Hit struct {
	Kind         string `json:"kind"`
	ID           string `json:"id"`
	Title        string `json:"title"`
	Subtitle     string `json:"subtitle"`
	MatchedField string `json:"matchedField"`
}
type Group struct {
	Module string `json:"module"`
	Kind   string `json:"kind"`
	Hits   []Hit  `json:"hits"`
}
type Result struct {
	Keyword   string  `json:"keyword"`
	TotalHits int     `json:"totalHits"`
	Groups    []Group `json:"groups"`
}
type Contributor interface {
	ModuleName() string
	Kind() string
	Search(context.Context, string, int) ([]Hit, error)
}
type Service struct {
	Contributors []Contributor
	Logger       *slog.Logger
}

func Strip(s string) string {
	return strings.TrimFunc(s, func(r rune) bool {
		return r >= 9 && r <= 13 || r >= 0x1c && r <= 0x20 || r == 0x1680 || r >= 0x2000 && r <= 0x2006 || r >= 0x2008 && r <= 0x200a || r == 0x2028 || r == 0x2029 || r == 0x205f || r == 0x3000
	})
}
func Pattern(s string) string {
	return "%" + strings.NewReplacer("\\", "\\\\", "%", "\\%", "_", "\\_").Replace(strings.ToLower(Strip(s))) + "%"
}
func (s *Service) Search(ctx context.Context, keyword string, limit int) (Result, error) {
	keyword = Strip(keyword)
	r := Result{Keyword: keyword, Groups: []Group{}}
	if keyword == "" {
		return r, api.NewBusinessError(api.ValidationFailed, "搜索词不能为空")
	}
	if len(utf16.Encode([]rune(keyword))) > 100 {
		return r, api.NewBusinessError(api.ValidationFailed, "搜索词长度不能超过 100 个字符")
	}
	limit = max(1, min(20, limit))
	contributors := append([]Contributor{}, s.Contributors...)
	sort.SliceStable(contributors, func(i, j int) bool { return contributors[i].ModuleName() < contributors[j].ModuleName() })
	for _, c := range contributors {
		hits, e := c.Search(ctx, keyword, limit)
		if ctx.Err() != nil {
			return r, ctx.Err()
		}
		if e != nil {
			if s.Logger != nil {
				s.Logger.WarnContext(ctx, "搜索模块不可用", "module", c.ModuleName())
			}
			continue
		}
		if len(hits) > 0 {
			r.Groups = append(r.Groups, Group{c.ModuleName(), c.Kind(), hits})
			r.TotalHits += len(hits)
		}
	}
	return r, nil
}
func (s *Service) Handler(logger *slog.Logger) http.Handler {
	return web.HandleErrors(func(w http.ResponseWriter, r *http.Request) error {
		if r.URL.Path != "/api/v1/search" {
			return web.NotFound()
		}
		if r.Method != "GET" && r.Method != "HEAD" {
			return web.MethodNotSupported(r.Method, []string{"GET"})
		}
		q := r.URL.Query()
		if !q.Has("keyword") {
			return web.MissingParameter("keyword")
		}
		limit := 5
		if q.Get("limit") != "" {
			n, e := strconv.ParseInt(q.Get("limit"), 10, 32)
			if e != nil {
				return web.InvalidParameter("limit")
			}
			limit = int(n)
		}
		result, e := s.Search(r.Context(), q.Get("keyword"), limit)
		if e != nil {
			return e
		}
		media, ok := web.SelectJSONMediaType(r.Header.Get("Accept"), []string{"application/json", "application/*+json"})
		if !ok {
			return web.NotAcceptable()
		}
		if r.Method == "HEAD" {
			w.Header().Set("Content-Type", media)
			return nil
		}
		return api.WriteControllerMediaJSON(w, 200, api.OK(result), media)
	}, logger)
}
