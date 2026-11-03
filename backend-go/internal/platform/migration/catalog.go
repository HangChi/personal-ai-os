// Package migration 校验显式 SQL 目录，不自行连接数据库或运行生产迁移。
package migration

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Script 保存原始字节的 SQL 与 SHA-256。SHA256 不是 Flyway CRC32 checksum。
type Script struct {
	Version int
	Name    string
	SHA256  string
	SQL     string
}

var versionedName = regexp.MustCompile(`^V([1-9][0-9]*)__([A-Za-z0-9][A-Za-z0-9_]*)\.sql$`)

// LoadVersioned 只支持当前 Java 的 V1..Vn 单层整数版本目录。
// 重复、缺号、不可读、不支持的 SQL 或嵌套目录全部失败，不跳过部分脚本。
// 仅无关的非 SQL 普通文件可忽略；不展开占位符、不拆句、不改换行或编码。
func LoadVersioned(source fs.FS) ([]Script, error) {
	if source == nil {
		return nil, errors.New("必须显式提供 SQL 文件系统")
	}
	files, err := fs.ReadDir(source, ".")
	if err != nil {
		return nil, fmt.Errorf("读取 SQL 目录失败: %w", err)
	}
	var scripts []Script
	for _, file := range files {
		if !file.Type().IsRegular() {
			return nil, fmt.Errorf("SQL 目录不允许子目录、符号链接或特殊文件: %s", file.Name())
		}
		if !strings.HasSuffix(strings.ToLower(file.Name()), ".sql") {
			continue
		}
		matches := versionedName.FindStringSubmatch(file.Name())
		if matches == nil {
			return nil, fmt.Errorf("不支持的 SQL 文件名: %s", file.Name())
		}
		version, err := strconv.Atoi(matches[1])
		if err != nil {
			return nil, fmt.Errorf("SQL 版本超出整数范围: %s", file.Name())
		}
		contents, err := fs.ReadFile(source, file.Name())
		if err != nil {
			return nil, fmt.Errorf("读取迁移 %s 失败: %w", file.Name(), err)
		}
		if !utf8.Valid(contents) || strings.ContainsRune(string(contents), '\x00') || strings.TrimSpace(string(contents)) == "" {
			return nil, fmt.Errorf("迁移必须为非空 UTF-8 且不能含 NUL: %s", file.Name())
		}
		digest := sha256.Sum256(contents)
		scripts = append(scripts, Script{Version: version, Name: file.Name(), SHA256: hex.EncodeToString(digest[:]), SQL: string(contents)})
	}
	if len(scripts) == 0 {
		return nil, errors.New("SQL 目录没有版本迁移")
	}
	sort.Slice(scripts, func(i, j int) bool { return scripts[i].Version < scripts[j].Version })
	for i, script := range scripts {
		if script.Version != i+1 {
			return nil, fmt.Errorf("SQL 版本必须从 V1 连续且唯一: 期望 V%d，得到 V%d", i+1, script.Version)
		}
	}
	return scripts, nil
}
