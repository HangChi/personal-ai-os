package model

import (
	"context"
	"database/sql/driver"
	"testing"
	"time"
)

func profileRow(active bool) []driver.Value {
	return sqlValues("p", "配置", nil, "https://fixture.invalid", nil, "chat", 0.7, nil, nil, "OPENAI", active, 0, 1, time.Now(), time.Now())
}
func requireProfile(active bool) []sqlStep {
	return []sqlStep{queryStep("WHERE id=$1", profileRow(active)), queryStep("FROM ai_model_profile_model", sqlValues(1, "CHAT", "chat", 0, 4096, time.Now()))}
}
func modelStore(t *testing.T, steps ...sqlStep) *PostgresReadStore {
	codec, e := NewKeyCodec("")
	if e != nil {
		t.Fatal(e)
	}
	s, e := NewPostgresReadStore(contractPool(t, steps...), codec)
	if e != nil {
		t.Fatal(e)
	}
	return s
}
func TestModelProfileStorageLifecycleContract(t *testing.T) {
	for _, mode := range []string{"create", "update", "activate", "alreadyActive", "rename", "delete", "deleteActive", "deleteLast", "reorder"} {
		t.Run(mode, func(t *testing.T) {
			steps := []sqlStep{terminalStep("begin"), execStep("LOCK TABLE ai_model_profile")}
			if mode == "create" {
				steps = append(steps, queryStep("INSERT INTO ai_model_profile(", sqlValues("p")))
			} else if mode == "reorder" {
				steps = append(steps, queryStep("ORDER BY sort_order", sqlValues("p"), sqlValues("q")), execStep("SET sort_order"), execStep("SET sort_order"))
			} else {
				steps = append(steps, requireProfile(mode == "alreadyActive" || mode == "deleteActive")...)
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
			case "deleteLast":
				steps = append(steps, queryStep("COUNT(*)", sqlValues(1)))
			}
			end := "commit"
			fail := mode == "deleteActive" || mode == "deleteLast"
			if fail {
				end = "rollback"
			}
			steps = append(steps, terminalStep(end))
			s := modelStore(t, steps...)
			ctx := context.Background()
			name, chat, emb, audio := "配置", "chat", "embed", "audio"
			w := 8192
			c := ProfileCommand{Name: &name, ChatModels: []*string{&chat, &chat, nil}, EmbeddingModels: []*string{&emb}, AudioModels: []*string{&audio}, ContextWindows: map[string]*int{"chat": &w}}
			var e error
			switch mode {
			case "create":
				_, e = s.Create(ctx, c)
			case "update":
				_, e = s.Update(ctx, "p", c)
			case "activate", "alreadyActive":
				_, e = s.Activate(ctx, "p")
			case "rename":
				_, e = s.Rename(ctx, "p", " 新名 ")
			case "delete", "deleteActive", "deleteLast":
				e = s.Delete(ctx, "p")
			case "reorder":
				e = s.Reorder(ctx, []string{"q", "q", "unknown"})
			}
			if (e != nil) != fail {
				t.Fatal(e)
			}
		})
	}
}
func TestModelStorageReadAndLegacyContract(t *testing.T) {
	ctx := context.Background()
	now := time.Now()
	steps := []sqlStep{queryStep("FROM ai_model_config", sqlValues("https://fixture.invalid", nil, "chat", 0.7, nil, nil, 1, now)), queryStep("UNION ALL"), terminalStep("begin"), queryStep("ORDER BY sort_order", profileRow(true)), queryStep("ORDER BY kind", sqlValues(1, "p", "CHAT", "chat", 0, 4096, now)), terminalStep("commit"), terminalStep("begin")}
	steps = append(steps, requireProfile(false)...)
	steps = append(steps, terminalStep("commit"))
	s := modelStore(t, steps...)
	if v, ok, e := s.Legacy(ctx); e != nil || !ok || (v.Revision == nil || *v.Revision != 1) {
		t.Fatal(v, ok, e)
	}
	if v, e := s.Current(ctx); e != nil || v.Revision != nil {
		t.Fatal(v, e)
	}
	if p, e := s.Profiles(ctx); e != nil || len(p) != 1 || len(p[0].Models) != 1 {
		t.Fatal(p, e)
	}
	if p, e := s.Require(ctx, "p"); e != nil || len(p.Models) != 1 {
		t.Fatal(p, e)
	}
}
func TestModelLegacySaveContract(t *testing.T) {
	ctx := context.Background()
	s := modelStore(t, terminalStep("begin"), execStep("LOCK TABLE ai_model_config"), queryStep("SELECT api_key", sqlValues(nil)), execStep("INSERT INTO ai_model_config"), queryStep("SELECT base_url", sqlValues("https://fixture.invalid", nil, "chat", 0.7, nil, nil, 2, time.Now())), terminalStep("commit"))
	if v, e := s.Save(ctx, SaveCommand{}); e != nil || (v.Revision == nil || *v.Revision != 2) {
		t.Fatal(v, e)
	}
}
