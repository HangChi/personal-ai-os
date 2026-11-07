package chat

import (
	"bytes"
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"personal-ai-os/backend-go/internal/business/dataexport"
	"testing"
	"time"
)

func referenceJSON(t *testing.T, object bool, ids ...string) string {
	t.Helper()
	list := []Attachment{}
	for _, id := range ids {
		if id == "" {
			list = append(list, Attachment{})
		} else {
			list = append(list, Attachment{ID: ptr(id)})
		}
	}
	var v any = list
	if object {
		v = map[string]any{"attachments": list}
	}
	b, e := json.Marshal(v)
	if e != nil {
		t.Fatal(e)
	}
	return string(b)
}
func cleanupSteps(rows ...[]driver.Value) []sqlStep {
	return []sqlStep{terminalStep("begin"), execStep("pg_advisory_xact_lock"), queryStep("SELECT attachments", rows...), terminalStep("commit")}
}
func TestAttachmentReferencesAndPruneProtectRecoverableInputsContract(t *testing.T) {
	ctx := context.Background()
	idA := "00000000-0000-0000-0000-000000000001"
	idB := "00000000-0000-0000-0000-000000000002"
	idC := "00000000-0000-0000-0000-000000000003"
	idD := "00000000-0000-0000-0000-000000000004"
	rows := [][]driver.Value{sqlValues(referenceJSON(t, false, idA, "")), sqlValues(referenceJSON(t, true, idB))}
	steps := []sqlStep{terminalStep("begin"), queryStep("SELECT attachments", rows...), terminalStep("commit")}
	steps = append(steps, cleanupSteps(rows...)...)
	steps = append(steps, cleanupSteps(rows...)...)
	a := &Attachments{Pool: contractPool(t, steps...), Directory: t.TempDir()}
	for _, id := range []string{idA, idB, idC, idD, "ignore.txt"} {
		p := filepath.Join(a.Directory, id)
		if e := os.WriteFile(p, []byte("content"), 0600); e != nil {
			t.Fatal(e)
		}
		if id != idD {
			at := time.Now().Add(-48 * time.Hour)
			if e := os.Chtimes(p, at, at); e != nil {
				t.Fatal(e)
			}
		}
	}
	refs, e := a.References(ctx)
	if e != nil || len(refs) != 2 || !refs[idA] || !refs[idB] {
		t.Fatal(refs, e)
	}
	n, e := a.Prune(ctx, time.Now())
	if e != nil || n != 1 {
		t.Fatal(n, e)
	}
	for _, id := range []string{idA, idB, idD, "ignore.txt"} {
		if _, e := os.Stat(filepath.Join(a.Directory, id)); e != nil {
			t.Fatal(id, e)
		}
	}
	a.Cleanup(ctx, []Attachment{{ID: ptr(idA)}, {ID: ptr(idD)}, {}})
	if _, e = os.Stat(filepath.Join(a.Directory, idD)); !errors.Is(e, os.ErrNotExist) {
		t.Fatal(e)
	}
	if _, e = os.Stat(filepath.Join(a.Directory, idA)); e != nil {
		t.Fatal("referenced object removed", e)
	}
	a.Cleanup(ctx, nil)
}
func TestAttachmentCorruptReferencesFailClosedContract(t *testing.T) {
	for _, mode := range []string{"json", "query", "scan", "rows"} {
		t.Run(mode, func(t *testing.T) {
			q := queryStep("SELECT attachments", sqlValues("{broken"))
			switch mode {
			case "query":
				q.err = errors.New("db")
			case "scan":
				q.rows = [][]driver.Value{sqlValues(nil)}
			case "rows":
				q.rows = nil
				q.rowErr = errors.New("read")
			}
			a := &Attachments{Pool: contractPool(t, terminalStep("begin"), execStep("pg_advisory_xact_lock"), q, terminalStep("rollback")), Directory: t.TempDir()}
			id := "00000000-0000-0000-0000-000000000001"
			p := filepath.Join(a.Directory, id)
			_ = os.WriteFile(p, []byte("keep"), 0600)
			if _, e := a.Prune(context.Background(), time.Now().Add(72*time.Hour)); e == nil {
				t.Fatal("unsafe cleanup")
			}
			if _, e := os.Stat(p); e != nil {
				t.Fatal("file deleted on unreadable snapshot")
			}
		})
	}
}
func TestChatExportNestedMessagesAndCitationsContract(t *testing.T) {
	steps := []sqlStep{terminalStep("begin"), queryStep("SELECT count(*) FROM chat_session", sqlValues(1)), queryStep("ORDER BY s.conversation_id", sessionRow()), queryStep("FROM chat_message WHERE", sqlValues(1, "USER", "question", 1, time.Now().UTC(), nil), sqlValues(2, "ASSISTANT", "answer", 2, time.Now().UTC(), nil)), queryStep("FROM chat_message_citation", sqlValues(2, 1, "00000000-0000-0000-0000-000000000001", "source", "source.md", 0, "evidence")), terminalStep("commit")}
	pool := contractPool(t, steps...)
	var b bytes.Buffer
	c := ExportContributor{}
	if c.Key() != "sessions" {
		t.Fatal(c.Key())
	}
	e := pool.WithTx(context.Background(), sql.TxOptions{ReadOnly: true}, func(tx *sql.Tx) error {
		n, e := c.Write(context.Background(), tx, &dataexport.Writer{Output: &b})
		if e == nil && n != 1 {
			t.Fatal(n)
		}
		return e
	})
	if e != nil || !json.Valid(b.Bytes()) || !bytes.Contains(b.Bytes(), []byte("evidence")) {
		t.Fatal(b.String(), e)
	}
}
