package model

import (
	"context"
	"database/sql/driver"
	"errors"
	"fmt"
	"testing"
	"time"
)

// Each fault must abort at the injected boundary. No later SQL write is allowed.
func TestModelStorageFailureSweep(t *testing.T) {
	ctx := context.Background()
	name, chat, emb, audio := "配置", "chat", "embed", "audio"
	c := ProfileCommand{Name: &name, ChatModels: []*string{&chat}, EmbeddingModels: []*string{&emb}, AudioModels: []*string{&audio}}
	for _, mode := range []string{"create", "update", "activate", "rename", "delete", "reorder", "require", "save"} {
		steps := []sqlStep{terminalStep("begin"), execStep("LOCK TABLE ai_model_profile")}
		switch mode {
		case "create":
			steps = append(steps, queryStep("INSERT INTO ai_model_profile(", sqlValues("p")))
		case "reorder":
			steps = append(steps, queryStep("ORDER BY sort_order", sqlValues("p"), sqlValues("q")), execStep("SET sort_order"), execStep("SET sort_order"))
		case "require":
			steps = []sqlStep{terminalStep("begin")}
			steps = append(steps, requireProfile(false)...)
		case "save":
			steps = []sqlStep{terminalStep("begin"), execStep("LOCK TABLE ai_model_config"), queryStep("SELECT api_key", sqlValues(nil)), execStep("INSERT INTO ai_model_config"), queryStep("SELECT base_url", sqlValues("https://fixture.invalid", nil, "chat", 0.7, nil, nil, 2, time.Now()))}
		default:
			steps = append(steps, requireProfile(false)...)
		}
		switch mode {
		case "update":
			steps = append(steps, execStep("UPDATE ai_model_profile SET name"))
			fallthrough
		case "create":
			steps = append(steps, execStep("DELETE FROM ai_model_profile_model"), execStep("INSERT INTO ai_model_profile_model"), execStep("INSERT INTO ai_model_profile_model"), execStep("INSERT INTO ai_model_profile_model"))
			steps = append(steps, requireProfile(false)...)
		case "activate":
			steps = append(steps, execStep("SET active=false"), execStep("SET active=true"))
			steps = append(steps, requireProfile(true)...)
		case "rename":
			steps = append(steps, execStep("SET name=$2"))
			steps = append(steps, requireProfile(false)...)
		case "delete":
			steps = append(steps, queryStep("COUNT(*)", sqlValues(2)), execStep("DELETE FROM ai_model_profile"))
		}
		steps = append(steps, terminalStep("commit"))
		for i, target := range steps {
			for _, fault := range []string{"statement", "rows", "scan"} {
				if fault != "statement" && target.op != "query" {
					continue
				}
				if fault == "scan" && target.match == "SELECT api_key" {
					continue
				}
				t.Run(fmt.Sprintf("%s/%d/%s", mode, i, fault), func(t *testing.T) {
					failure := errors.New("injected model store failure")
					script := append([]sqlStep{}, steps[:i+1]...)
					switch fault {
					case "statement":
						script[i].err = failure
					case "rows":
						script[i].rows = nil
						script[i].rowErr = failure
					case "scan":
						script[i].rows = [][]driver.Value{sqlValues(nil)}
					}
					if i > 0 && target.op != "commit" {
						script = append(script, terminalStep("rollback"))
					}
					s := modelStore(t, script...)
					var e error
					switch mode {
					case "create":
						_, e = s.Create(ctx, c)
					case "update":
						_, e = s.Update(ctx, "p", c)
					case "activate":
						_, e = s.Activate(ctx, "p")
					case "rename":
						_, e = s.Rename(ctx, "p", "new")
					case "delete":
						e = s.Delete(ctx, "p")
					case "reorder":
						e = s.Reorder(ctx, []string{"q", "p"})
					case "require":
						_, e = s.Require(ctx, "p")
					case "save":
						_, e = s.Save(ctx, SaveCommand{})
					}
					if e == nil {
						t.Fatal("fault swallowed")
					}
				})
			}
		}
	}
}
