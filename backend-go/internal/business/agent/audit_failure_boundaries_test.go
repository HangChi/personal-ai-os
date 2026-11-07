package agent

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
	"time"
)

func TestAuditStorageFailuresDoNotPresentPartialHistory(t *testing.T) {
	id := "10000000-0000-4000-8000-000000000001"
	row := sqlValues(1, "delete", "TOOL", "REQUIRES_CONFIRMATION", 4, "c", time.Now(), "turn", "digest", `{"confirmationId":"10000000-0000-4000-8000-000000000001"}`)
	base := []sqlStep{terminalStep("begin"), queryStep("count(*)", sqlValues(1)), queryStep("request_digest,response_payload", row), queryStep("FROM confirmation", sqlValues(id, "c", "PENDING")), terminalStep("commit")}
	for i, target := range base {
		for _, fault := range []string{"statement", "scan", "rows"} {
			if fault != "statement" && target.op != "query" {
				continue
			}
			t.Run(fmt.Sprintf("%d/%s", i, fault), func(t *testing.T) {
				steps := append([]sqlStep{}, base[:i+1]...)
				switch fault {
				case "statement":
					steps[i].err = errors.New("audit unavailable")
				case "scan":
					steps[i].rows = [][]driver.Value{sqlValues(nil)}
				case "rows":
					steps[i].rows = nil
					steps[i].rowErr = errors.New("cursor failed")
				}
				if i > 0 && target.op != "commit" {
					steps = append(steps, terminalStep("rollback"))
				}
				if _, e := (&Audit{Pool: contractPool(t, steps...)}).Search(context.Background(), "c", "", 0, 0); e == nil {
					t.Fatal("failed audit accepted")
				}
			})
		}
	}
	a := &Audit{Pool: contractPool(t, terminalStep("begin"), queryStep("count(*)", sqlValues(1)), terminalStep("commit"))}
	if page, e := a.Search(context.Background(), "", "", 2147483647, 100); e != nil || len(page.Items) != 0 {
		t.Fatal(page, e)
	}
}
