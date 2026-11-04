package config

import (
	"fmt"
	"maps"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

// BindingSource是显式提供的字符串来源；切片顺序为从高到低。
// Environment仅启用Spring SystemEnvironment键映射，不读取进程环境。
// Values由调用方持有，绑定期间不能并发修改；结果不保存这些map引用。
type BindingSource struct {
	Environment bool
	Values      map[string]string `json:"-"`
}

func (source BindingSource) String() string {
	return fmt.Sprintf("BindingSource{environment:%t,keys:%d}", source.Environment, len(source.Values))
}
func (source BindingSource) GoString() string { return source.String() }

// BindingSource显式复制.env的字面值，便于按原dotenv-systemEnvironment语义绑定。
// 不访问文件，也不自行提升来源优先级。
func (source DotEnv) BindingSource() BindingSource {
	return BindingSource{Environment: true, Values: maps.Clone(source.values)}
}

// MCPProperties/MCPServerConfig只描述配置，不启动命令、连接服务器或授权工具。
// 可变容器全部私有，访问返回副本；格式化/JSON不会输出命令/参数/环境凭据。
type MCPProperties struct {
	enabled bool
	servers map[string]MCPServerConfig
}

type MCPServerConfig struct {
	command        *string
	args           []string
	environment    map[string]string
	autoTools      []string
	connectTimeout time.Duration
}

func (properties MCPProperties) Enabled() bool { return properties.enabled }
func (properties MCPProperties) ServerNames() []string {
	names := slices.Collect(maps.Keys(properties.servers))
	slices.Sort(names)
	return names
}
func (properties MCPProperties) Server(name string) (MCPServerConfig, bool) {
	value, found := properties.servers[name]
	return value, found
}
func (properties MCPProperties) String() string {
	return fmt.Sprintf("MCPProperties{enabled:%t,servers:%d}", properties.enabled, len(properties.servers))
}
func (properties MCPProperties) GoString() string  { return properties.String() }
func (MCPProperties) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func (server MCPServerConfig) Command() *string {
	if server.command == nil {
		return nil
	}
	value := *server.command
	return &value
}
func (server MCPServerConfig) Args() []string                 { return append([]string{}, server.args...) }
func (server MCPServerConfig) Environment() map[string]string { return maps.Clone(server.environment) }
func (server MCPServerConfig) AutoTools() []string            { return append([]string{}, server.autoTools...) }
func (server MCPServerConfig) ConnectTimeout() time.Duration  { return server.connectTimeout }
func (MCPServerConfig) String() string                        { return "MCPServerConfig{redacted}" }
func (server MCPServerConfig) GoString() string               { return server.String() }
func (MCPServerConfig) MarshalJSON() ([]byte, error)          { return []byte(`{"redacted":true}`), nil }

type mcpListInput struct {
	scalar  *string
	indexed map[int]string
	invalid bool
}
type mcpServerInput struct {
	direct      *string
	envDirect   *string
	command     *string
	timeout     *string
	args        mcpListInput
	tools       mcpListInput
	environment map[string]string
}
type mcpSourceInput struct {
	direct        *string
	serversDirect *string
	enabled       *string
	servers       map[string]*mcpServerInput
}
type propertyToken struct {
	value   string
	bracket bool
}

// BindMCPProperties覆盖原record的动态server/map/list/set与构造器归一化。
// 来源只来自参数；不展开占位符或加载YAML/profile。未知字段与原默认Binder一样忽略。
func BindMCPProperties(sources ...BindingSource) (MCPProperties, error) {
	return bindMCPResolved(func(s string) (string, error) { return s, nil }, sources...)
}

func bindMCPResolved(resolve func(string) (string, error), sources ...BindingSource) (MCPProperties, error) {
	parsed := make([]mcpSourceInput, 0, len(sources))
	names := make(map[string]struct{})
	for _, source := range sources {
		value, err := parseMCPSource(source)
		if err != nil {
			return MCPProperties{}, err
		}
		parsed = append(parsed, value)
		for name := range value.servers {
			names[name] = struct{}{}
		}
	}
	result := MCPProperties{servers: make(map[string]MCPServerConfig)}
	enabledBound := false
	for _, source := range parsed {
		if source.enabled == nil {
			continue
		}
		text, err := resolve(*source.enabled)
		if err != nil {
			return MCPProperties{}, err
		}
		value, err := bindBoolean(map[string]string{"mcp.enabled": text}, "mcp.enabled", false)
		if err != nil {
			return MCPProperties{}, err
		}
		result.enabled = value
		enabledBound = true
		break
	}
	for _, name := range slices.Sorted(maps.Keys(names)) {
		inputs := make([]*mcpServerInput, 0, len(parsed))
		for _, source := range parsed {
			if input := source.servers[name]; input != nil {
				inputs = append(inputs, input)
			}
		}
		server, bound, err := bindMCPServer(inputs, resolve)
		if err != nil {
			return MCPProperties{}, err
		}
		if bound {
			result.servers[name] = server
		}
	}
	serversBound := len(result.servers) > 0
	if !serversBound {
		for _, source := range parsed {
			if source.serversDirect == nil {
				continue
			}
			if *source.serversDirect != "" {
				return MCPProperties{}, &propertyError{"mcp.servers", ErrInvalidProperty}
			}
			serversBound = true
			break
		}
	}
	if !enabledBound && !serversBound {
		for _, source := range parsed {
			if source.direct == nil {
				continue
			}
			if *source.direct != "" {
				return MCPProperties{}, &propertyError{"mcp", ErrInvalidProperty}
			}
			break
		}
	}
	return result, nil
}

func bindMCPServer(inputs []*mcpServerInput, resolve func(string) (string, error)) (MCPServerConfig, bool, error) {
	server := MCPServerConfig{args: []string{}, environment: make(map[string]string), autoTools: []string{}, connectTimeout: 10 * time.Second}
	var timeout *string
	var direct, envDirect *string
	var args, tools *mcpListInput
	for _, input := range inputs {
		if direct == nil {
			direct = input.direct
		}
		if envDirect == nil {
			envDirect = input.envDirect
		}
		if server.command == nil && input.command != nil {
			value := *input.command
			server.command = &value
		}
		if timeout == nil {
			timeout = input.timeout
		}
		if args == nil && (input.args.scalar != nil || len(input.args.indexed) > 0 || input.args.invalid) {
			args = &input.args
		}
		if tools == nil && (input.tools.scalar != nil || len(input.tools.indexed) > 0 || input.tools.invalid) {
			tools = &input.tools
		}
		for key, value := range input.environment {
			if _, found := server.environment[key]; !found {
				server.environment[key] = value
			}
		}
	}
	bound := server.command != nil || args != nil || tools != nil || len(server.environment) > 0
	if server.command != nil {
		v, e := resolve(*server.command)
		if e != nil {
			return MCPServerConfig{}, false, e
		}
		server.command = &v
	}
	for k, v := range server.environment {
		v, e := resolve(v)
		if e != nil {
			return MCPServerConfig{}, false, e
		}
		server.environment[k] = v
	}
	if len(server.environment) == 0 && envDirect != nil {
		if *envDirect != "" {
			return MCPServerConfig{}, false, &propertyError{"mcp.servers.env", ErrInvalidProperty}
		}
		bound = true
	}
	if timeout != nil {
		text, e := resolve(*timeout)
		if e != nil {
			return MCPServerConfig{}, false, e
		}
		value, err := durationNanos(text)
		if err != nil {
			return MCPServerConfig{}, false, &propertyError{"mcp.servers.connect-timeout", ErrInvalidProperty}
		}
		if value != nil {
			bound = true
			if value.Sign() > 0 {
				if !value.IsInt64() {
					return MCPServerConfig{}, false, &propertyError{"mcp.servers.connect-timeout", ErrScalarOverflow}
				}
				server.connectTimeout = time.Duration(value.Int64())
			}
		}
	}
	if args != nil {
		input, err := resolveMCPList(*args, resolve)
		if err != nil {
			return MCPServerConfig{}, false, err
		}
		value, err := bindMCPList(input)
		if err != nil {
			return MCPServerConfig{}, false, err
		}
		server.args = value
	}
	if tools != nil {
		input, err := resolveMCPList(*tools, resolve)
		if err != nil {
			return MCPServerConfig{}, false, err
		}
		value, err := bindMCPList(input)
		if err != nil {
			return MCPServerConfig{}, false, err
		}
		slices.Sort(value)
		server.autoTools = slices.Compact(value)
	}
	if !bound && direct != nil {
		if *direct != "" {
			return MCPServerConfig{}, false, &propertyError{"mcp.servers", ErrInvalidProperty}
		}
		bound = true
	}
	return server, bound, nil
}

func resolveMCPList(input mcpListInput, resolve func(string) (string, error)) (mcpListInput, error) {
	if input.scalar != nil {
		v, e := resolve(*input.scalar)
		if e != nil {
			return input, e
		}
		input.scalar = &v
		return input, nil
	}
	input.indexed = maps.Clone(input.indexed)
	for k, v := range input.indexed {
		v, e := resolve(v)
		if e != nil {
			return input, e
		}
		input.indexed[k] = v
	}
	return input, nil
}

func bindMCPList(input mcpListInput) ([]string, error) {
	if input.scalar != nil {
		if *input.scalar == "" {
			return []string{}, nil
		}
		parts := strings.Split(*input.scalar, ",")
		// StringToCollection用String.trim；不是Java strip或shell/CSV转义。
		for i := range parts {
			parts[i] = strings.TrimFunc(parts[i], func(r rune) bool { return r <= 0x20 })
		}
		return parts, nil
	}
	if input.invalid {
		return nil, &propertyError{"mcp.servers.list", ErrInvalidProperty}
	}
	values := make([]string, len(input.indexed))
	for index, value := range input.indexed {
		if index < 0 || index >= len(values) {
			return nil, &propertyError{"mcp.servers.list", ErrInvalidProperty}
		}
		values[index] = value
	}
	return values, nil
}

func parseMCPSource(source BindingSource) (mcpSourceInput, error) {
	result := mcpSourceInput{servers: make(map[string]*mcpServerInput)}
	// 显式map没有插入顺序；固定键顺序，与离线对照来源的TreeMap一致。
	for _, original := range slices.Sorted(maps.Keys(source.Values)) {
		if !utf8.ValidString(original) {
			return mcpSourceInput{}, &propertyError{"mcp", ErrInvalidProperty}
		}
		key := original
		if source.Environment {
			key = environmentBindingKey(key)
		}
		tokens, err := splitPropertyKey(key)
		if err != nil {
			if strings.HasPrefix(strings.ToLower(key), "mcp.") {
				return mcpSourceInput{}, &propertyError{"mcp", ErrInvalidProperty}
			}
			continue
		}
		if len(tokens) < 1 || uniformName(tokens[0].value) != "mcp" {
			continue
		}
		value := source.Values[original]
		if len(tokens) == 1 {
			if result.direct == nil {
				copy := value
				result.direct = &copy
			}
			continue
		}
		if len(tokens) == 2 && uniformName(tokens[1].value) == "enabled" {
			if result.enabled == nil {
				copy := value
				result.enabled = &copy
			}
			continue
		}
		if uniformName(tokens[1].value) != "servers" {
			continue
		}
		if len(tokens) == 2 {
			if result.serversDirect == nil {
				copy := value
				result.serversDirect = &copy
			}
			continue
		}
		name := tokens[2].value
		if len(tokens) == 3 {
			input := result.servers[name]
			if input == nil {
				input = &mcpServerInput{environment: make(map[string]string)}
				result.servers[name] = input
			}
			if input.direct == nil {
				copy := value
				input.direct = &copy
			}
			continue
		}
		field := tokens[3:]
		if name == "" {
			continue
		}
		fieldName := uniformName(field[0].value)
		// SystemEnvironment把AUTO_TOOLS/CONNECT_TIMEOUT映射为多段，原Binder允许对应字段。
		if source.Environment && len(field) >= 2 && !field[1].bracket {
			if joined := fieldName + uniformName(field[1].value); joined == "autotools" || joined == "connecttimeout" {
				fieldName, field = joined, append([]propertyToken{{value: joined}}, field[2:]...)
			}
		}
		known := (len(field) == 1 && (fieldName == "command" || fieldName == "connecttimeout" || fieldName == "args" || fieldName == "autotools")) ||
			(len(field) > 1 && (fieldName == "args" || fieldName == "autotools")) || fieldName == "env"
		if !known {
			continue
		}
		if !utf8.ValidString(value) || !utf8.ValidString(name) {
			return mcpSourceInput{}, &propertyError{"mcp.servers", ErrInvalidProperty}
		}
		input := result.servers[name]
		if input == nil {
			input = &mcpServerInput{environment: make(map[string]string)}
			result.servers[name] = input
		}
		switch fieldName {
		case "command":
			if input.command == nil {
				copy := value
				input.command = &copy
			}
		case "connecttimeout":
			if input.timeout == nil {
				copy := value
				input.timeout = &copy
			}
		case "args", "autotools":
			list := &input.args
			if fieldName == "autotools" {
				list = &input.tools
			}
			if len(field) == 1 {
				if list.scalar == nil {
					copy := value
					list.scalar = &copy
				}
				continue
			}
			index, err := strconv.Atoi(field[1].value)
			if len(field) != 2 || !field[1].bracket || err != nil || index < 0 || strconv.Itoa(index) != field[1].value {
				list.invalid = true
				continue
			}
			if list.indexed == nil {
				list.indexed = make(map[int]string)
			}
			if _, found := list.indexed[index]; !found {
				list.indexed[index] = value
			}
		case "env":
			if len(field) == 1 {
				if input.envDirect == nil {
					copy := value
					input.envDirect = &copy
				}
				continue
			}
			parts := make([]string, len(field)-1)
			for i, token := range field[1:] {
				parts[i] = token.value
			}
			envKey := strings.Join(parts, ".")
			if _, found := input.environment[envKey]; !found {
				input.environment[envKey] = value
			}
		}
	}
	return result, nil
}

func uniformName(value string) string {
	return strings.Map(func(r rune) rune {
		if r >= 'A' && r <= 'Z' {
			return r + ('a' - 'A')
		}
		if r == '-' || r == '_' {
			return -1
		}
		return r
	}, value)
}

func environmentBindingKey(key string) string {
	parts := strings.FieldsFunc(key, func(r rune) bool { return r == '_' || r == '.' })
	var result strings.Builder
	for _, part := range parts {
		if _, err := strconv.ParseUint(part, 10, 32); err == nil {
			result.WriteString("[" + part + "]")
		} else {
			if result.Len() > 0 {
				result.WriteByte('.')
			}
			result.WriteString(strings.ToLower(part))
		}
	}
	return result.String()
}

func splitPropertyKey(key string) ([]propertyToken, error) {
	var tokens []propertyToken
	for len(key) > 0 {
		if key[0] == '.' {
			key = key[1:]
			continue
		}
		if key[0] == '[' {
			end := strings.IndexByte(key, ']')
			if end < 0 {
				return nil, ErrInvalidProperty
			}
			tokens = append(tokens, propertyToken{value: key[1:end], bracket: true})
			key = key[end+1:]
			continue
		}
		end := strings.IndexAny(key, ".[=")
		if end < 0 {
			end = len(key)
		}
		if end == 0 {
			return nil, ErrInvalidProperty
		}
		// 普通map元素剥除未转义标点；方括号元素则保留原样。
		value := strings.Map(func(r rune) rune {
			if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '-' || r == '_' {
				return r
			}
			return -1
		}, key[:end])
		if value != "" {
			tokens = append(tokens, propertyToken{value: value})
		}
		key = key[end:]
	}
	return tokens, nil
}
