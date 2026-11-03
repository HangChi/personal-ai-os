package migration

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
)

func TestFlywayChecksumMatchesRealJava42ScriptsAndBoundaries(t *testing.T) {
	data, err := os.ReadFile("testdata/java-flyway-checksums.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string `json:"baseline_commit"`
		Version  string `json:"flyway_version"`
		Scripts  []struct {
			Name     string `json:"name"`
			Checksum int32  `json:"checksum"`
		} `json:"scripts"`
		Cases []struct {
			Name     string `json:"name"`
			SQL      string `json:"sql"`
			Checksum int32  `json:"checksum"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || fixture.Version != "12.4.0" || len(fixture.Scripts) != 42 || len(fixture.Cases) != 12 {
		t.Fatal("实际Java快照基线/数量不符")
	}
	for _, row := range fixture.Scripts {
		sql, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "backend", "src", "main", "resources", "db", "migration", row.Name))
		if err != nil {
			t.Fatal(err)
		}
		if checksum, err := FlywayChecksum(string(sql)); err != nil || checksum != row.Checksum {
			t.Fatalf("原迁移CRC32不符: %s: %v", row.Name, err)
		}
	}
	for _, row := range fixture.Cases {
		t.Run(row.Name, func(t *testing.T) {
			if checksum, err := FlywayChecksum(row.SQL); err != nil || checksum != row.Checksum {
				t.Fatal("实际Java边界CRC32不符", err)
			}
		})
	}
	if _, err := FlywayChecksum(string([]byte{0xff})); err == nil {
		t.Fatal("接受非法UTF-8")
	}
}

func testHistory(t *testing.T) ([]expectedMigration, []historyRow) {
	t.Helper()
	scripts, err := LoadVersioned(fstest.MapFS{"V1__one.sql": {Data: []byte("SELECT 1;")}, "V2__two_words.sql": {Data: []byte("SELECT 2;")}})
	if err != nil {
		t.Fatal(err)
	}
	expected, err := expectedHistory(scripts)
	if err != nil {
		t.Fatal(err)
	}
	var rows []historyRow
	for i, script := range expected {
		rows = append(rows, historyRow{rank: i + 1, version: nullable(script.version), description: script.description, kind: "SQL", script: script.script, checksum: checksumPointer(script.checksum), success: true})
	}
	return expected, rows
}

func nullable(value string) *string      { return &value }
func checksumPointer(value int32) *int32 { return &value }

func TestHistoryRejectsEveryMismatchAndUnsupportedMarker(t *testing.T) {
	expected, rows := testHistory(t)
	if err := validateHistoryRows(expected, rows); err != nil {
		t.Fatal(err)
	}
	for name, modify := range map[string]func([]historyRow) []historyRow{
		"missing":       func(rows []historyRow) []historyRow { return rows[:1] },
		"extra":         func(rows []historyRow) []historyRow { return append(rows, rows[1]) },
		"failed":        func(rows []historyRow) []historyRow { rows[0].success = false; return rows },
		"checksum":      func(rows []historyRow) []historyRow { rows[0].checksum = checksumPointer(0); return rows },
		"null checksum": func(rows []historyRow) []historyRow { rows[0].checksum = nil; return rows },
		"wrong version": func(rows []historyRow) []historyRow { rows[0].version = nullable("99"); return rows },
		"null version":  func(rows []historyRow) []historyRow { rows[0].version = nil; return rows },
		"description":   func(rows []historyRow) []historyRow { rows[0].description = "other"; return rows },
		"script":        func(rows []historyRow) []historyRow { rows[0].script = "V1__other.sql"; return rows },
		"rank":          func(rows []historyRow) []historyRow { rows[1].rank = rows[0].rank; return rows },
		"out of order":  func(rows []historyRow) []historyRow { rows[0], rows[1] = rows[1], rows[0]; return rows },
		"baseline":      func(rows []historyRow) []historyRow { rows[0].kind = "BASELINE"; rows[0].checksum = nil; return rows },
		"schema marker": func(rows []historyRow) []historyRow { rows[0].kind = "SCHEMA"; rows[0].rank = 0; return rows },
		"repeatable":    func(rows []historyRow) []historyRow { rows[0].version = nil; rows[0].kind = "SQL"; return rows },
		"jdbc":          func(rows []historyRow) []historyRow { rows[0].kind = "JDBC"; return rows },
	} {
		t.Run(name, func(t *testing.T) {
			copyRows := append([]historyRow(nil), rows...)
			if err := validateHistoryRows(expected, modify(copyRows)); !errors.Is(err, ErrInvalidHistory) {
				t.Fatal("不兼容历史未拒绝", err)
			}
		})
	}
}

func TestHistoryValidatesExplicitCatalogAndSchema(t *testing.T) {
	if _, err := NewHistoryValidator(nil, nil, "public"); err == nil {
		t.Fatal("隐式连接/目录被接受")
	}
	if _, err := expectedHistory(nil); err == nil {
		t.Fatal("空目录被接受")
	}
	scripts, err := LoadVersioned(fstest.MapFS{"V1__one.sql": {Data: []byte("SELECT 1;")}})
	if err != nil {
		t.Fatal(err)
	}
	scripts[0].SHA256 = "bad"
	if _, err := expectedHistory(scripts); err == nil {
		t.Fatal("原SQL摘要不一致被接受")
	}
	for _, schema := range []string{"", "public;DROP TABLE tasks", "a.b", strings.Repeat("a", 64)} {
		if validHistorySchema(schema) {
			t.Fatal("非法schema被接受")
		}
	}
	if !validHistorySchema("public") || !validHistorySchema("go_history_probe") {
		t.Fatal("正常schema被拒绝")
	}
}
