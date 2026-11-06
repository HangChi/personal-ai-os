package migration

import (
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
)

func TestCatalogUsesNumericOrderAndExactBytes(t *testing.T) {
	source := fstest.MapFS{"README.md": {Data: []byte("ignored")}}
	for version := 1; version <= 12; version++ {
		source[fmt.Sprintf("V%d__probe.sql", version)] = &fstest.MapFile{Data: []byte("-- 中文\r\nSELECT 1;\r\n")}
	}
	entries, err := LoadVersioned(source)
	if err != nil || len(entries) != 12 {
		t.Fatalf("加载失败：%v", err)
	}
	for i, entry := range entries {
		if entry.Version != i+1 || entry.SQL != "-- 中文\r\nSELECT 1;\r\n" || len(entry.SHA256) != 64 {
			t.Fatal("必须按数字排序且不改写 SQL 字节")
		}
	}
}

func TestCatalogFailsClosedOnOmissionsAndUnsupportedScripts(t *testing.T) {
	for name, source := range map[string]fstest.MapFS{
		"empty":           {},
		"starts after V1": {"V2__test.sql": {Data: []byte("SELECT 1;")}},
		"gap":             {"V1__test.sql": {Data: []byte("SELECT 1;")}, "V3__test.sql": {Data: []byte("SELECT 3;")}},
		"duplicate":       {"V1__one.sql": {Data: []byte("SELECT 1;")}, "V1__two.sql": {Data: []byte("SELECT 1;")}},
		"zero":            {"V0__test.sql": {Data: []byte("SELECT 1;")}},
		"leading zero":    {"V01__test.sql": {Data: []byte("SELECT 1;")}},
		"overflow":        {"V999999999999999999999__test.sql": {Data: []byte("SELECT 1;")}},
		"repeatable":      {"R__test.sql": {Data: []byte("SELECT 1;")}},
		"undo":            {"U1__test.sql": {Data: []byte("SELECT 1;")}},
		"uppercase":       {"V1__test.SQL": {Data: []byte("SELECT 1;")}},
		"dotted version":  {"V1.1__test.sql": {Data: []byte("SELECT 1;")}},
		"blank":           {"V1__test.sql": {Data: []byte(" \r\n")}},
		"invalid UTF8":    {"V1__test.sql": {Data: []byte{0xff}}},
		"NUL":             {"V1__test.sql": {Data: []byte("SELECT\x001;")}},
		"subdirectory":    {"nested/V1__test.sql": {Data: []byte("SELECT 1;")}},
		"symlink":         {"V1__test.sql": {Mode: fs.ModeSymlink, Data: []byte("elsewhere")}},
	} {
		t.Run(name, func(t *testing.T) {
			if entries, err := LoadVersioned(source); err == nil || entries != nil {
				t.Fatal("存在遗漏或不支持的脚本时不能返回部分成功目录")
			}
		})
	}
	if _, err := LoadVersioned(nil); err == nil {
		t.Fatal("不能隐式选择 SQL 目录")
	}
}

// 验证固定 Java 基线的全部 42 个原文件，而不是从 Go 目录生成 expected。
func TestCatalogMatchesAllBaselineSQLHashes(t *testing.T) {
	root := filepath.Join("..", "..", "..", "..")
	const prefix = "backend/src/main/resources/db/migration/"
	entries, err := LoadVersioned(os.DirFS(filepath.Join(root, filepath.FromSlash(prefix))))
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(filepath.Join(root, "specs", "033-go-rewrite", "inventory.json"))
	if err != nil {
		t.Fatal(err)
	}
	var manifest struct {
		Baseline string `json:"baseline_commit"`
		Files    []struct {
			Path   string `json:"path"`
			SHA256 string `json:"sha256"`
		} `json:"files"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		t.Fatal(err)
	}
	if manifest.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" {
		t.Fatal("Java 对照基线改变，需显式审查")
	}
	expected := map[string]string{}
	for _, file := range manifest.Files {
		if strings.HasPrefix(file.Path, prefix) && strings.HasSuffix(file.Path, ".sql") {
			expected[strings.TrimPrefix(file.Path, prefix)] = file.SHA256
		}
	}
	if len(entries) != 44 || len(expected) != 42 {
		t.Fatalf("原42迁移与034追加迁移不能遗漏：actual=%d baseline=%d", len(entries), len(expected))
	}
	for _, entry := range entries {
		// 034 开始允许追加产品迁移；原42个文件仍必须逐字匹配固定基线。
		if _, baseline := expected[entry.Name]; !baseline {
			if entry.Name != "V43__task_workspace.sql" && entry.Name != "V44__calendar_exchange.sql" {
				t.Errorf("未经本阶段审查的新迁移：%s", entry.Name)
			}
			continue
		}
		if entry.SHA256 != expected[entry.Name] {
			t.Errorf("原迁移字节改变或目录遗漏：%s", entry.Name)
		}
		delete(expected, entry.Name)
	}
	if len(expected) != 0 {
		t.Fatal("基线还有未载入的迁移")
	}
}
