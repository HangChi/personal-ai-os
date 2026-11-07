package search

import (
	"context"
	"database/sql"
	"strings"
	"testing"
	"time"
)

func TestSearchSQLContributorsContract(t *testing.T) {
	for _, kind := range []string{"chat", "memory", "calendar", "knowledge"} {
		t.Run(kind, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin")}
			switch kind {
			case "chat":
				steps = append(steps, queryStep("FROM chat_session", sqlValues("session", "a", "标题", "2条消息", "标题")), queryStep("FROM chat_message", sqlValues("session", "a", "标题", "重复", "消息内容"), sqlValues("session", "b", "正文", "2条消息", "消息内容")))
			case "memory":
				steps = append(steps, queryStep("FROM memory", sqlValues("a", strings.Repeat("界", 50), time.Now())))
			case "calendar":
				steps = append(steps, queryStep("FROM calendar_event", sqlValues("a", "标题", nil, "关键词地点", time.Now(), "WEEKLY")))
			case "knowledge":
				steps = append(steps, queryStep("FROM knowledge_document", sqlValues("document", "a", "标题", "2分块", "标题")), queryStep("FROM knowledge_document", sqlValues("document", "b", "正文", "3分块", "正文")))
			}
			steps = append(steps, terminalStep("commit"))
			pool := contractPool(t, steps...)
			var got []Hit
			e := pool.WithTx(context.Background(), sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error {
				var e error
				switch kind {
				case "chat":
					got, e = chatQuery(context.Background(), tx, "关键词", 2)
				case "memory":
					got, e = memoryQuery(context.Background(), tx, "关键词", 2)
				case "calendar":
					got, e = calendarQuery(context.Background(), tx, "关键词", 2)
				case "knowledge":
					got, e = knowledgeQuery(context.Background(), tx, "public.vector_store", "关键词", 2)
				}
				return e
			})
			if e != nil || len(got) == 0 {
				t.Fatal(got, e)
			}
			if (kind == "chat" || kind == "knowledge") && (len(got) != 2 || got[0].ID == got[1].ID) {
				t.Fatal(got)
			}
			if kind == "memory" && !strings.HasSuffix(got[0].Title, "…") {
				t.Fatal(got)
			}
			if kind == "calendar" && got[0].MatchedField != "地点" {
				t.Fatal(got)
			}
		})
	}
}
