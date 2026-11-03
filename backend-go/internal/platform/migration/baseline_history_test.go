package migration

import (
	"errors"
	"testing"
)

func TestBaselineV1RequiresExplicitPolicyAndExactMarker(t *testing.T) {
	expected, rows := testHistory(t)
	rows[0] = historyRow{rank: 1, version: nullable("1"), description: "<< Flyway Baseline >>", kind: "BASELINE", script: "<< Flyway Baseline >>", success: true}
	if err := validateHistoryRows(expected, rows); !errors.Is(err, ErrInvalidHistory) {
		t.Fatal("默认策略不能默许baseline", err)
	}
	if baseline, err := validateHistoryWithBaselineV1(expected, rows, true); err != nil || baseline != 1 {
		t.Fatal("明确策略拒绝合法V1标记", baseline, err)
	}
	for name, change := range map[string]func([]historyRow) []historyRow{
		"version":          func(r []historyRow) []historyRow { r[0].version = nullable("2"); return r },
		"null version":     func(r []historyRow) []historyRow { r[0].version = nil; return r },
		"rank":             func(r []historyRow) []historyRow { r[0].rank = 0; return r },
		"description":      func(r []historyRow) []historyRow { r[0].description = "custom-sensitive-baseline"; return r },
		"script":           func(r []historyRow) []historyRow { r[0].script = "custom-sensitive-baseline"; return r },
		"checksum":         func(r []historyRow) []historyRow { r[0].checksum = checksumPointer(0); return r },
		"failed":           func(r []historyRow) []historyRow { r[0].success = false; return r },
		"kind":             func(r []historyRow) []historyRow { r[0].kind = "SCHEMA"; return r },
		"missing SQL":      func(r []historyRow) []historyRow { return r[:1] },
		"duplicate marker": func(r []historyRow) []historyRow { r[1] = r[0]; r[1].rank = 2; return r },
		"SQL checksum":     func(r []historyRow) []historyRow { r[1].checksum = checksumPointer(0); return r },
		"order":            func(r []historyRow) []historyRow { r[1].rank = 1; return r },
	} {
		t.Run(name, func(t *testing.T) {
			copyRows := append([]historyRow(nil), rows...)
			if baseline, err := validateHistoryWithBaselineV1(expected, change(copyRows), true); !errors.Is(err, ErrInvalidHistory) || baseline != 0 {
				t.Fatal("不受支持的基线必须失败关闭", baseline, err)
			}
		})
	}
	expected, normal := testHistory(t)
	if baseline, err := validateHistoryWithBaselineV1(expected, normal, true); err != nil || baseline != 0 {
		t.Fatal("允许baseline不应强制要求baseline", baseline, err)
	}
}
