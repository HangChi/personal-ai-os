package chat

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"reflect"
	"sort"
	"strings"
	"testing"
	"time"
)

type oracleStore struct{ tx Tx }

func (s oracleStore) Transact(_ context.Context, _ bool, action func(Tx) error) error {
	return action(s.tx)
}

type oracleTx struct{ serviceTx }

func (tx *oracleTx) InsertSession(_ context.Context, s Session) error { tx.session = s; return nil }
func (tx *oracleTx) Messages(_ context.Context, _ string, q MessageQuery) ([]Message, error) {
	result := []Message{}
	for _, m := range tx.messages {
		if q.Limit == nil || q.BeforeSeq == nil || m.SequenceNo < *q.BeforeSeq {
			result = append(result, m)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i].SequenceNo < result[j].SequenceNo })
	if q.Limit != nil && len(result) > *q.Limit {
		result = result[len(result)-*q.Limit:]
	}
	return result, nil
}
func TestOriginalJavaChatStorageContracts(t *testing.T) {
	data, err := os.ReadFile("testdata/java-chat-storage.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string            `json:"baseline_commit"`
		Sources  map[string]string `json:"sources_sha256"`
		Inputs   struct {
			Bindings []struct{ Method, Path, Body, ContentType string }
			Exports  []struct {
				Title, Zone string
				Messages    []Message
			}
			Snapshots []*string
			Queries   []struct {
				Keyword    string
				Page, Size int
			}
			Titles []struct {
				Title, Content string
				Count          int64
			}
		}
		Expected map[string][]json.RawMessage
	}
	if err = json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(fixture.Sources) != 22 {
		t.Fatal("invalid baseline")
	}
	compare := func(t *testing.T, kind string, i int, value any) {
		t.Helper()
		got, _ := json.Marshal(value)
		var a, b any
		if err := json.Unmarshal(got, &a); err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal(fixture.Expected[kind][i], &b); err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(a, b) {
			t.Errorf("%s[%d]\n got %s\nwant %s", kind, i, got, fixture.Expected[kind][i])
		}
	}
	when := time.Date(2026, 10, 27, 2, 0, 0, 0, time.UTC)
	zone, _ := time.LoadLocation("Asia/Shanghai")
	t.Run("bindings", func(t *testing.T) {
		for i, c := range fixture.Inputs.Bindings {
			tx := &oracleTx{serviceTx: serviceTx{session: Session{ConversationID: "c-existing", Title: "旧标题", CreatedAt: when, UpdatedAt: when.Add(time.Minute)}, count: 7}}
			for seq := 1; seq <= 7; seq++ {
				role := "USER"
				if seq%2 == 0 {
					role = "ASSISTANT"
				}
				tx.messages = append(tx.messages, Message{ID: int64(seq), Role: role, Content: "m" + string(rune('0'+seq)), SequenceNo: seq, CreatedAt: when.Add(-time.Duration(seq) * time.Second), Citations: []Citation{}, Attachments: []Attachment{}})
			}
			svc, _ := NewService(oracleStore{tx}, nil)
			handler, _ := NewHTTP(svc, zone, slog.New(slog.NewTextHandler(io.Discard, nil)))
			req := httptest.NewRequest(c.Method, c.Path, strings.NewReader(c.Body))
			req.Header.Set("Content-Type", c.ContentType)
			response := httptest.NewRecorder()
			handler.ServeHTTP(response, req)
			var body map[string]any
			if e := json.Unmarshal(response.Body.Bytes(), &body); e != nil {
				t.Fatal(e)
			}
			if obj, ok := body["data"].(map[string]any); ok {
				if _, ok = obj["conversationId"]; ok {
					delete(obj, "createdAt")
					delete(obj, "updatedAt")
					if c.Method == "POST" {
						obj["conversationId"] = "<generated>"
					}
				}
			}
			compare(t, "bindings", i, map[string]any{"status": response.Code, "code": body["code"], "message": body["message"], "data": body["data"]})
		}
	})
	t.Run("exports", func(t *testing.T) {
		for i, c := range fixture.Inputs.Exports {
			zone, e := time.LoadLocation(c.Zone)
			if e != nil {
				t.Fatal(e)
			}
			doc := RenderMarkdown(Session{Title: c.Title, CreatedAt: when}, c.Messages, zone)
			compare(t, "exports", i, map[string]string{"filename": doc.Filename, "markdown": doc.Markdown, "encodedFilename": doc.EncodedFilename})
		}
	})
	t.Run("snapshots", func(t *testing.T) {
		for i, raw := range fixture.Inputs.Snapshots {
			compare(t, "snapshots", i, snapshots(raw))
		}
	})
	t.Run("queries", func(t *testing.T) {
		for i, c := range fixture.Inputs.Queries {
			q := (SessionQuery{Keyword: c.Keyword, Page: c.Page, Size: c.Size}).Normalize()
			pattern := ""
			if strip(q.Keyword) != "" {
				pattern = likePattern(q.Keyword)
			}
			compare(t, "queries", i, map[string]any{"page": q.Page, "size": q.Size, "pattern": pattern})
		}
	})
	t.Run("titles", func(t *testing.T) {
		for i, c := range fixture.Inputs.Titles {
			tx := &oracleTx{serviceTx: serviceTx{session: Session{ConversationID: "c-existing", Title: c.Title}, count: c.Count}}
			svc, _ := NewService(oracleStore{tx}, nil)
			if e := svc.InitializeTitle(context.Background(), "c-existing", &c.Content); e != nil {
				t.Fatal(e)
			}
			compare(t, "titles", i, tx.session.Title)
		}
	})
}
