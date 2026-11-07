package knowledge

import (
	"context"
	"database/sql/driver"
	"errors"
	"testing"

	"personal-ai-os/backend-go/internal/platform/coordination"
)

type relayPublisher struct {
	calls int
	err   error
}

func (p *relayPublisher) Publish(context.Context, string, string) error { p.calls++; return p.err }

func TestSignalRelayStorageFailuresReleaseLeaseAndStopPublishing(t *testing.T) {
	failure := errors.New("synthetic relay failure")
	for _, stage := range []string{"acquire", "occupied", "query", "scan", "cursor", "unknown-topic", "update", "publish", "success"} {
		t.Run(stage, func(t *testing.T) {
			steps := acquireDocumentSteps()
			if stage == "acquire" {
				steps = []sqlStep{{op: "begin", err: failure}}
			}
			if stage == "occupied" {
				steps[2].rows = nil
			}
			publisher := &relayPublisher{}
			if stage == "publish" {
				publisher.err = failure
			}
			if stage != "acquire" && stage != "occupied" {
				topic := "index"
				if stage == "unknown-topic" {
					topic = "unknown"
				}
				read := queryStep("SELECT topic,item_id::text,revision", sqlValues(topic, contractDocA, 3))
				switch stage {
				case "query":
					read.err = failure
				case "scan":
					read.rows = [][]driver.Value{sqlValues(nil)}
				case "cursor":
					read.rows = nil
					read.rowErr = failure
				}
				steps = append(steps, read)
				if stage != "query" && stage != "scan" && stage != "cursor" {
					update := execStep("UPDATE work_signal_outbox SET next_publish")
					update.check = func(args []driver.NamedValue) {
						expected := int64(30)
						if stage == "unknown-topic" || stage == "publish" {
							expected = 5
						}
						if args[0].Value != expected || args[1].Value != topic || args[2].Value != contractDocA || args[3].Value != int64(3) {
							t.Errorf("unexpected relay update: %v", args)
						}
					}
					if stage == "update" {
						update.err = failure
					}
					steps = append(steps, terminalStep("begin"), queryStep("SELECT slot FROM runtime_lease", sqlValues(0)), update)
					if stage == "update" {
						steps = append(steps, terminalStep("rollback"))
					} else {
						steps = append(steps, terminalStep("commit"))
					}
				}
				steps = append(steps, releaseDocumentSteps()...)
			}
			store := contractStore(t, steps...)
			locks, err := coordination.New(store.Pool)
			if err != nil {
				t.Fatal(err)
			}
			defer locks.Close(context.Background())
			store.Locks = locks
			relay := &SignalRelay{Store: store, Publisher: publisher, IndexQueue: "fixture.index", Stats: NewStats()}
			err = relay.Relay(context.Background())
			wantError := stage != "occupied" && stage != "success"
			if (err != nil) != wantError {
				t.Fatalf("error=%v, want failure=%v", err, wantError)
			}
			wantCalls := 0
			if stage == "update" || stage == "publish" || stage == "success" {
				wantCalls = 1
			}
			if publisher.calls != wantCalls {
				t.Fatalf("publishes=%d, want %d", publisher.calls, wantCalls)
			}
		})
	}
}
