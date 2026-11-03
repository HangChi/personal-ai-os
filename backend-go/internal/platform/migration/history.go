package migration

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"hash/crc32"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"personal-ai-os/backend-go/internal/platform/database"
)

// FlywayChecksum 对照固定 Java Flyway 12.4.0：忽略 CR/LF 和开头单个 BOM，
// 保留其他 Unicode/空白/注释；CRC32转有符号32位。不是原字节来源SHA-256。
func FlywayChecksum(sql string) (int32, error) {
	if !utf8.ValidString(sql) {
		return 0, errors.New("Flyway checksum 输入必须是有效 UTF-8")
	}
	normalized := strings.NewReplacer("\r", "", "\n", "").Replace(strings.TrimPrefix(sql, "\ufeff"))
	return int32(crc32.ChecksumIEEE([]byte(normalized))), nil
}

var ErrInvalidHistory = errors.New("Flyway 迁移历史与显式目录不兼容")

// HistoryError 只输出固定原因，不输出SQL/数据库消息或历史表中未受信任的文本。
type HistoryError struct {
	Reason string
	cause  error
}

func (err *HistoryError) Error() string    { return "Flyway 迁移历史校验失败：" + err.Reason }
func (err *HistoryError) GoString() string { return err.Error() }
func (err *HistoryError) Unwrap() []error {
	if err.cause == nil {
		return []error{ErrInvalidHistory}
	}
	return []error{ErrInvalidHistory, err.cause}
}

type expectedMigration struct {
	version, description, script string
	checksum                     int32
}

type historyRow struct {
	rank              int
	version           *string
	description, kind string
	script            string
	checksum          *int32
	success           bool
}

type HistoryStatus struct {
	// Applied只计成功SQL记录，不把BASELINE标记当作实际执行的脚本。
	Applied         int
	CurrentVersion  int
	BaselineVersion int
}

type HistoryValidator struct {
	pool            *database.Pool
	schema          string
	expected        []expectedMigration
	allowBaselineV1 bool
}

var historySchema = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]{0,62}$`)

func validHistorySchema(schema string) bool { return historySchema.MatchString(schema) }

func expectedHistory(scripts []Script) ([]expectedMigration, error) {
	if len(scripts) == 0 {
		return nil, errors.New("必须显式提供全部原迁移目录")
	}
	expected := make([]expectedMigration, 0, len(scripts))
	for i, script := range scripts {
		parts := versionedName.FindStringSubmatch(script.Name)
		checksum, err := FlywayChecksum(script.SQL)
		digest := sha256.Sum256([]byte(script.SQL))
		if script.Version != i+1 || len(parts) != 3 || parts[1] != strconv.Itoa(script.Version) || err != nil ||
			script.SHA256 != hex.EncodeToString(digest[:]) || len(parts[2]) > 200 || strings.TrimSpace(script.SQL) == "" || strings.ContainsRune(script.SQL, '\x00') {
			return nil, errors.New("显式原迁移目录顺序/内容或命名不受支持")
		}
		expected = append(expected, expectedMigration{version: parts[1], description: strings.ReplaceAll(parts[2], "_", " "), script: script.Name, checksum: checksum})
	}
	return expected, nil
}

// NewHistoryValidator 只支持已验证的整数版本SQL历史；不执行migrate/baseline/repair/clean。
// schema必须显式指定。BASELINE/SCHEMA/重复迁移/自定义历史类型拒绝，待独立兼容验收。
func NewHistoryValidator(pool *database.Pool, scripts []Script, schema string) (*HistoryValidator, error) {
	if pool == nil || !validHistorySchema(schema) {
		return nil, errors.New("Flyway 校验必须显式提供连接池和受支持的 schema")
	}
	expected, err := expectedHistory(scripts)
	if err != nil {
		return nil, err
	}
	return &HistoryValidator{pool: pool, schema: schema, expected: expected}, nil
}

// NewHistoryValidatorWithBaselineV1 仅显式兼容基线Java默认baseline-on-migrate标记。
// 仍不执行baseline或放过V2之后的checksum；不证明被跳过的V1实际结构/扩展完整。
// 自定义基线版本/描述/SCHEMA等历史仍拒绝，部署必须另做完整schema验证。
func NewHistoryValidatorWithBaselineV1(pool *database.Pool, scripts []Script, schema string) (*HistoryValidator, error) {
	validator, err := NewHistoryValidator(pool, scripts, schema)
	if err != nil {
		return nil, err
	}
	validator.allowBaselineV1 = true
	return validator, nil
}

func validateHistoryRows(expected []expectedMigration, rows []historyRow) error {
	_, err := validateHistoryWithBaselineV1(expected, rows, false)
	return err
}

func validateHistoryWithBaselineV1(expected []expectedMigration, rows []historyRow, allow bool) (int, error) {
	if len(expected) == 0 || len(rows) != len(expected) {
		return 0, &HistoryError{Reason: "迁移数量缺失或超出目录"}
	}
	previousRank := 0
	baseline := 0
	for i, expected := range expected {
		row := rows[i]
		if i == 0 && allow && row.kind == "BASELINE" {
			if expected.version != "1" || row.rank != 1 || row.version == nil || *row.version != "1" || !row.success || row.checksum != nil || row.description != "<< Flyway Baseline >>" || row.script != "<< Flyway Baseline >>" {
				return 0, &HistoryError{Reason: "基线标记不受支持"}
			}
			previousRank = 1
			baseline = 1
			continue
		}
		if row.rank <= previousRank || row.kind != "SQL" || !row.success || row.version == nil || *row.version != expected.version ||
			row.description != expected.description || row.script != expected.script {
			return 0, &HistoryError{Reason: "存在失败、乱序或不支持的迁移记录"}
		}
		if row.checksum == nil || *row.checksum != expected.checksum {
			return 0, &HistoryError{Reason: "迁移 checksum 不匹配"}
		}
		previousRank = row.rank
	}
	return baseline, nil
}

// Validate 在只读repeatable-read事务中取得一致历史快照。不修改Flyway表或争抢其迁移锁；
// 必须由部署流程先独占完成Java/Flyway迁移，Go本函数不能授权并发迁移或证明DDL完整。
func (validator *HistoryValidator) Validate(ctx context.Context) (HistoryStatus, error) {
	var history []historyRow
	err := validator.pool.WithTx(ctx, sql.TxOptions{ReadOnly: true, Isolation: sql.LevelRepeatableRead}, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT installed_rank,version,description,type,script,checksum,success FROM "`+validator.schema+`"."flyway_schema_history" ORDER BY installed_rank LIMIT $1`, len(validator.expected)+1)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var row historyRow
			if err := rows.Scan(&row.rank, &row.version, &row.description, &row.kind, &row.script, &row.checksum, &row.success); err != nil {
				return err
			}
			history = append(history, row)
		}
		return rows.Err()
	})
	if err != nil {
		return HistoryStatus{}, &HistoryError{Reason: "读取历史失败", cause: err}
	}
	baseline, err := validateHistoryWithBaselineV1(validator.expected, history, validator.allowBaselineV1)
	if err != nil {
		return HistoryStatus{}, err
	}
	return HistoryStatus{Applied: len(history) - baseline, CurrentVersion: len(validator.expected), BaselineVersion: baseline}, nil
}
