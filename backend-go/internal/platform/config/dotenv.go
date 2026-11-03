package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Lookup 与 os.LookupEnv 同形；显式空字符串也必须阻止低优先级回落。
type Lookup func(string) (string, bool)

// DotEnv 保留 Java LinkedHashMap 的首次插入顺序与最后一次赋值。
// 字段不导出，格式化对象只显示键数量，防止误把凭据写入日志。
type DotEnv struct {
	keys   []string
	values map[string]string
}

func (source DotEnv) Lookup(key string) (string, bool) {
	value, found := source.values[key]
	return value, found
}

func (source DotEnv) Keys() []string   { return append([]string(nil), source.keys...) }
func (source DotEnv) String() string   { return fmt.Sprintf("DotEnv{keys:%d}", len(source.keys)) }
func (source DotEnv) GoString() string { return source.String() }

// ParseDotEnv 只解析字面量键值，不执行 shell、变量替换或转义展开。
// Java Files.readAllLines 按 CR/LF/CRLF 分行，无法解码 UTF-8 时整份忽略。
func ParseDotEnv(data []byte) (DotEnv, error) {
	if !utf8.Valid(data) {
		return DotEnv{}, errors.New(".env 必须使用有效 UTF-8 编码")
	}
	source := DotEnv{values: make(map[string]string)}
	text := strings.ReplaceAll(string(data), "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	for _, raw := range strings.Split(text, "\n") {
		line := javaStrip(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if strings.HasPrefix(line, "export ") {
			line = javaStrip(strings.TrimPrefix(line, "export "))
		}
		key, value, found := strings.Cut(line, "=")
		key = javaStrip(key)
		if !found || key == "" {
			continue
		}
		value = javaStrip(value)
		if len(value) >= 2 && ((value[0] == '"' && value[len(value)-1] == '"') ||
			(value[0] == '\'' && value[len(value)-1] == '\'')) {
			value = value[1 : len(value)-1]
		}
		if _, exists := source.values[key]; !exists {
			source.keys = append(source.keys, key)
		}
		source.values[key] = value
	}
	return source, nil
}

// javaWhitespace 对齐 Character.isWhitespace，尤其保留不换行空格与 NEL。
func javaWhitespace(r rune) bool {
	return (r >= '\t' && r <= '\r') || (r >= '\u001c' && r <= '\u001f') ||
		(r != '\u00a0' && r != '\u2007' && r != '\u202f' && (unicode.Is(unicode.Zs, r) || r == '\u2028' || r == '\u2029'))
}

func javaStrip(value string) string { return strings.TrimFunc(value, javaWhitespace) }

// Environment 把系统环境放在 .env 之前；不导出或枚举凭据值。
type Environment struct {
	system Lookup
	dotenv DotEnv
	status string
}

func (env Environment) Lookup(key string) (string, bool) {
	if env.system != nil {
		if value, found := env.system(key); found {
			return value, true
		}
	}
	return env.dotenv.Lookup(key)
}

func (env Environment) Status() string       { return env.status }
func (env Environment) DotEnvKeys() []string { return env.dotenv.Keys() }
func (env Environment) String() string {
	return fmt.Sprintf("Environment{dotenv:%s,keys:%d}", env.status, len(env.dotenv.keys))
}
func (env Environment) GoString() string { return env.String() }

// LoadEnvironment 默认完全不接触文件系统。仅系统环境中的 GO_DOTENV_ENABLED
// 可开启迁移进程的 .env 查找；文件本身不能自行开启读取。
// enabled 后从当前目录向上最多查找三层，与 Java 相同；读取失败不阻止启动。
func LoadEnvironment(system Lookup, startDirectory string) (Environment, error) {
	if system == nil {
		return Environment{}, errors.New("环境变量读取函数不能为空")
	}
	env := Environment{system: system, status: "disabled"}
	enabled, err := boolean(system, "GO_DOTENV_ENABLED", false)
	if err != nil || !enabled {
		return env, err
	}
	enabled, err = boolean(system, "APP_DOTENV_ENABLED", true)
	if err != nil || !enabled {
		return env, err
	}
	if startDirectory == "" {
		startDirectory, err = os.Getwd()
		if err != nil {
			env.status = "unreadable"
			return env, nil
		}
	}
	dir, err := filepath.Abs(startDirectory)
	if err != nil {
		env.status = "unreadable"
		return env, nil
	}
	env.status = "not_found"
	for depth := 0; depth <= 3; depth++ {
		path := filepath.Join(dir, ".env")
		info, statErr := os.Stat(path)
		if statErr == nil && info.Mode().IsRegular() {
			data, readErr := os.ReadFile(path)
			if readErr != nil {
				env.status = "unreadable"
				return env, nil
			}
			source, parseErr := ParseDotEnv(data)
			if parseErr != nil {
				env.status = "unreadable"
				return env, nil
			}
			env.dotenv, env.status = source, "loaded"
			return env, nil
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			break
		}
		dir = parent
	}
	return env, nil
}
