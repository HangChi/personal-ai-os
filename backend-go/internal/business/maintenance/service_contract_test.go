package maintenance

import (
	"context"
	"database/sql/driver"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
)

func TestMaintenanceVerificationAndDrillContract(t *testing.T) {
	raw, _ := json.Marshal(map[string]string{"status": "UNVERIFIED"})
	result := string(raw)
	for _, mode := range []string{"verify", "binary", "drill", "rejected", "invalid-result", "query", "save"} {
		t.Run(mode, func(t *testing.T) {
			external := mode == "drill" || mode == "rejected" || mode == "invalid-result"
			q := queryStep("maintenance_verify_text", sqlValues(result))
			if external {
				q.match = "maintenance_drill_text"
			}
			if mode == "rejected" {
				q.rows = [][]driver.Value{sqlValues("{}")}
			}
			if mode == "invalid-result" {
				q.rows = [][]driver.Value{sqlValues("bad json")}
			}
			if mode == "query" {
				q.err = errors.New("db")
			}
			steps := []sqlStep{terminalStep("begin"), execStep("SET LOCAL"), q}
			fail := mode == "rejected" || mode == "invalid-result" || mode == "query" || mode == "save"
			if mode != "rejected" && mode != "invalid-result" && mode != "query" {
				save := queryStep("maintenance_save", sqlValues(result))
				if mode == "save" {
					save.err = errors.New("save")
				}
				steps = append(steps, save)
			}
			if fail {
				steps = append(steps, terminalStep("rollback"))
			} else {
				steps = append(steps, terminalStep("commit"))
			}
			s, e := NewService(contractPool(t, steps...), config.Role("api"))
			if e != nil {
				t.Fatal(e)
			}
			input := raw
			if mode == "binary" {
				input = []byte{0xff, 0}
			}
			var v map[string]any
			if external {
				v, e = s.Drill(context.Background(), input)
			} else {
				v, e = s.Verify(context.Background(), input)
			}
			if (e != nil) != fail {
				t.Fatal(v, e)
			}
			if !fail && v["status"] != "UNVERIFIED" {
				t.Fatal(v)
			}
			if len(s.slots) != 0 {
				t.Fatal("slot leaked")
			}
		})
	}
	s, _ := NewService(contractPool(t), config.Role("api"))
	for _, raw := range [][]byte{{0xff}, {0}, []byte(strings.Repeat("x", 16385))} {
		if _, e := s.Drill(context.Background(), raw); e == nil {
			t.Fatal("invalid drill accepted")
		}
	}
	if _, e := s.Verify(context.Background(), make([]byte, MaxBytes+1)); e == nil {
		t.Fatal("size limit")
	}
	s.slots <- struct{}{}
	s.slots <- struct{}{}
	if _, e := s.Verify(context.Background(), nil); e == nil {
		t.Fatal("admission limit")
	}
}
func TestMaintenanceSnapshotHeartbeatAndShutdownContract(t *testing.T) {
	s, e := NewService(contractPool(t, queryStep("maintenance_snapshot", sqlValues("{}")), execStep("INSERT INTO runtime_heartbeat"), execStep("DELETE FROM runtime_heartbeat")), config.Role("api"))
	if e != nil {
		t.Fatal(e)
	}
	if _, e = s.Snapshot(context.Background()); e != nil {
		t.Fatal(e)
	}
	if e = s.Heartbeat(context.Background()); e != nil {
		t.Fatal(e)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	stop := s.Start(ctx, slog.New(slog.NewTextHandler(io.Discard, nil)))
	stop()
	stop()
	if _, e := decode("broken"); e == nil {
		t.Fatal("invalid JSON")
	}
}
