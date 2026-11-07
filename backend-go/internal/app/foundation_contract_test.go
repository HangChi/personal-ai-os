package app

import (
	"context"
	"database/sql/driver"
	"errors"
	"io"
	"log/slog"
	"net/http/httptest"
	"os"
	"personal-ai-os/backend-go/internal/platform/config"
	"personal-ai-os/backend-go/internal/platform/migration"
	"strconv"
	"strings"
	"testing"
)

func foundationContractConnector(t *testing.T, deny bool) driver.Connector {
	t.Helper()
	scripts, e := migration.LoadVersioned(os.DirFS("../../../backend/src/main/resources/db/migration"))
	if e != nil {
		t.Fatal(e)
	}
	history := [][]driver.Value{}
	for _, s := range scripts {
		checksum, e := migration.FlywayChecksum(s.SQL)
		if e != nil {
			t.Fatal(e)
		}
		name := strings.TrimSuffix(strings.SplitN(s.Name, "__", 2)[1], ".sql")
		history = append(history, sqlValues(s.Version, strconv.Itoa(s.Version), strings.ReplaceAll(name, "_", " "), "SQL", s.Name, int64(checksum), true))
	}
	script := &sqlScript{t: t}
	script.responder = func(op, q string, a []driver.NamedValue) (sqlStep, error) {
		step := sqlStep{affected: 1}
		if op == "begin" || op == "commit" || op == "rollback" {
			return step, nil
		}
		if op == "exec" {
			if strings.Contains(q, "runtime_instance") {
				return step, nil
			}
			return step, errors.New("unexpected foundation write: " + q)
		}
		if op != "query" {
			return step, errors.New("unexpected foundation operation")
		}
		switch {
		case strings.Contains(q, "flyway_schema_history"):
			step.rows = history
		case strings.Contains(q, "has_table_privilege"), strings.Contains(q, "has_sequence_privilege"):
			step.rows = [][]driver.Value{sqlValues(!deny)}
		case strings.Contains(q, "SELECT format_type"):
			step.rows = [][]driver.Value{sqlValues("vector(1024)")}
		case strings.Contains(q, "count(*)"):
			step.rows = [][]driver.Value{sqlValues(0)}
		case q == "SELECT 1":
			step.rows = [][]driver.Value{sqlValues(1)}
		case strings.Contains(q, "maintenance_snapshot()"):
			step.rows = [][]driver.Value{sqlValues("{}")}
		case strings.Contains(q, "FROM task"), strings.Contains(q, "FROM ai_model_config"), strings.Contains(q, "FROM ai_model_profile"), strings.Contains(q, "FROM chat_context_preference"), strings.Contains(q, "FROM chat_run"), strings.Contains(q, "FROM knowledge_document"), strings.Contains(q, "FROM memory"), strings.Contains(q, "FROM calendar_event"), strings.Contains(q, "FROM chat_session"), strings.Contains(q, "FROM workflow"):
		default:
			return step, errors.New("unexpected foundation query: " + q)
		}
		return step, nil
	}
	return sqlConnector{script}
}
func TestFoundationCompleteAssemblyRoutesAndShutdownContract(t *testing.T) {
	for _, phase := range []string{"base", "p2", "p3", "p8", "denied"} {
		t.Run(phase, func(t *testing.T) {
			overrides := map[string]string{"app.runtime.role": "api", "app.auth.enabled": "false", "app.calendar.scan-enabled": "false", "app.memory.backfill.enabled": "false", "app.context.auto-memory.enabled": "false", "app.context.summary.enabled": "false", "app.chat.upload-dir": t.TempDir(), "app.chat.attachment-janitor-enabled": "false", "app.retention.cron": "-", "app.maintenance.heartbeat-enabled": "false", "app.workflows.enabled": "false", "spring.ai.model.chat": "none", "spring.ai.model.embedding": "none"}
			p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: overrides})
			if e != nil {
				t.Fatal(e)
			}
			o := FoundationOptions{Connector: foundationContractConnector(t, phase == "denied"), Migrations: os.DirFS("../../../backend/src/main/resources/db/migration"), Schema: "public", EnableP2: phase != "base", EnableP3: phase == "p3" || phase == "p8", EnableP4: phase == "p8", EnableP5: phase == "p8", EnableP6: phase == "p8", EnableP7: phase == "p8", EnableP8: phase == "p8"}
			f, e := OpenFoundation(context.Background(), p, o, slog.New(slog.NewTextHandler(io.Discard, nil)))
			if phase == "denied" {
				if e == nil || f != nil {
					t.Fatal("privilege bypass")
				}
				return
			}
			if e != nil {
				t.Fatal(e)
			}
			t.Cleanup(func() {
				if e := f.Close(context.Background()); e != nil {
					t.Error(e)
				}
			})
			if f.Pool() == nil || f.ModelGate() == nil || f.Executor() == nil || f.History().Applied == 0 {
				t.Fatal("incomplete foundation")
			}
			if phase == "p8" && (f.Models() == nil || f.Chat() == nil || f.Knowledge() == nil) {
				t.Fatal("modules missing")
			}
			for _, path := range []string{"/api/v1/health", "/api/v1/unknown", "/api/v1/tasks", "/api/v1/settings/model", "/api/v1/chat/tools", "/api/v1/memories", "/api/v1/calendar/events", "/api/v1/knowledge/documents", "/api/v1/workflows"} {
				r := httptest.NewRequest("GET", path, nil)
				w := httptest.NewRecorder()
				f.Handler().ServeHTTP(w, r)
				if w.Code >= 500 {
					t.Fatal(path, w.Code, w.Body.String())
				}
			}
			if e = f.Close(context.Background()); e != nil {
				t.Fatal(e)
			}
			if e = f.Close(context.Background()); e != nil {
				t.Fatal(e)
			}
			w := httptest.NewRecorder()
			f.Handler().ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/health", nil))
			if w.Code != 503 {
				t.Fatal("closed runtime accepted request", w.Code)
			}
		})
	}
}
