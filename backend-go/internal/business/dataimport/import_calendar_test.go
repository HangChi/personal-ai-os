package dataimport

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"testing"
	"time"
)

// Record the actual INSERT arguments without requiring a running PostgreSQL.
type calendarConnector struct{ recurrence *string }
type calendarConn struct{ recurrence *string }
type calendarTx struct{}

func (c calendarConnector) Connect(context.Context) (driver.Conn, error) {
	return calendarConn{c.recurrence}, nil
}
func (c calendarConnector) Driver() driver.Driver { return nil }
func (c calendarConn) Prepare(string) (driver.Stmt, error) {
	return nil, errors.New("unexpected prepare")
}
func (c calendarConn) Close() error              { return nil }
func (c calendarConn) Begin() (driver.Tx, error) { return calendarTx{}, nil }
func (calendarTx) Commit() error                 { return nil }
func (calendarTx) Rollback() error               { return nil }
func (c calendarConn) ExecContext(_ context.Context, _ string, args []driver.NamedValue) (driver.Result, error) {
	*c.recurrence = args[7].Value.(string)
	return driver.RowsAffected(1), nil
}

func TestImportCalendarPreservesAllRecurrenceRules(t *testing.T) {
	for _, rule := range []string{"NONE", "DAILY", "WEEKLY", "MONTHLY", "WEEKDAYS", " weekdays ", "YEARLY", "unknown", ""} {
		t.Run(rule, func(t *testing.T) {
			expected := rule
			switch rule {
			case " weekdays ":
				expected = "WEEKDAYS"
			case "YEARLY", "unknown", "":
				expected = "NONE"
			}
			var persisted string
			db := sql.OpenDB(calendarConnector{&persisted})
			defer db.Close()
			tx, err := db.Begin()
			if err != nil {
				t.Fatal(err)
			}
			defer tx.Rollback()
			id := UUID("00000000-0000-4000-8000-000000000001")
			at := time.Date(2026, 11, 5, 9, 0, 0, 0, time.UTC)
			version := "1"
			raw, err := json.Marshal(Payload{FormatVersion: &version, CalendarEvents: []*Calendar{{ID: &id, Title: "例会", StartsAt: &at, RecurrenceType: rule}}})
			if err != nil {
				t.Fatal(err)
			}
			restored, err := Parse(raw)
			if err != nil {
				t.Fatal(err)
			}
			count, err := importCalendar(context.Background(), tx, restored.CalendarEvents)
			if err != nil || count != 1 {
				t.Fatalf("import = %d, %v", count, err)
			}
			if persisted != expected {
				t.Fatalf("rule %q imported as %q, want %q", rule, persisted, expected)
			}
		})
	}
}
