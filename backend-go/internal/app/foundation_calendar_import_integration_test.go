//go:build integration

package app

import (
	"context"
	"database/sql"
	"testing"
	"time"
)

// Match Java's five-rule export/import regression against a real migrated DB.
func TestCalendarImportEveryRecurrenceRoundTripPostgres(t *testing.T) {
	f := p7Foundation(t, nil)
	for _, rule := range []string{"NONE", "DAILY", "WEEKLY", "MONTHLY", "WEEKDAYS"} {
		t.Run(rule, func(t *testing.T) {
			p7Clean(t, f)
			appExec(t, f, "INSERT INTO calendar_event(id,title,all_day,starts_at,ends_at,recurrence_type,recurrence_until,reminder_lead_minutes,created_at,updated_at) "+
				"VALUES('00000000-0000-0000-0000-000000000700','例会',false,'2026-11-05T09:00Z','2026-11-05T10:00Z','"+rule+"','2026-12-31T09:00Z',30,now(),now())")
			exported := p7Request(t, f, "GET", "/api/v1/export", nil)
			if exported.Code != 200 {
				t.Fatal(exported.Code, exported.Body.String())
			}
			appExec(t, f, "DELETE FROM calendar_event")
			restored := p7Request(t, f, "POST", "/api/v1/import", exported.Body.Bytes())
			if restored.Code != 200 || p7JSON(t, restored)["data"].(map[string]any)["calendarEvents"] != float64(1) {
				t.Fatal(restored.Code, restored.Body.String())
			}
			var actual string
			var lead int
			var until time.Time
			err := f.pool.WithConn(context.Background(), func(c *sql.Conn) error {
				return c.QueryRowContext(context.Background(), "SELECT recurrence_type,reminder_lead_minutes,recurrence_until FROM calendar_event WHERE id='00000000-0000-0000-0000-000000000700'").Scan(&actual, &lead, &until)
			})
			if err != nil {
				t.Fatal(err)
			}
			if actual != rule || lead != 30 || !until.Equal(time.Date(2026, 12, 31, 9, 0, 0, 0, time.UTC)) {
				t.Fatalf("lost recurrence/reminder state: got %s, %d, %s; want %s, 30, 2026-12-31T09:00Z", actual, lead, until, rule)
			}
		})
	}
}
