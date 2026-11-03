package config

import (
	_ "embed"
	"encoding/json"
	"errors"
	"maps"
	"regexp"
	"slices"
	"strings"
	"unicode/utf8"
)

// baselineData由真实YamlPropertySourceLoader从固定Git基线生成，哈希由脚本复验。
// 嵌入的是17份原始文档的扁平属性及未解析占位符，不含宿主变量或真实凭据。
//
//go:embed baseline.json
var baselineData string

// PropertyOptions只接收调用方显式提供的来源，绝不读取文件、宿主环境或远程配置。
type PropertyOptions struct {
	Overrides map[string]string `json:"-"`
	System    map[string]string `json:"-"`
	DotEnv    map[string]string `json:"-"`
}

func (PropertyOptions) String() string           { return "PropertyOptions{redacted}" }
func (options PropertyOptions) GoString() string { return options.String() }

// Properties保存已复制的来源。Get延迟解析占位符；相互独立的并发读取没有可变缓存。
type Properties struct {
	sources  []BindingSource
	profiles []string
	defaults []string
}

func (*Properties) String() string                { return "Properties{redacted}" }
func (properties *Properties) GoString() string   { return properties.String() }
func (*Properties) MarshalJSON() ([]byte, error)  { return []byte(`{"redacted":true}`), nil }
func (properties *Properties) Profiles() []string { return append([]string{}, properties.profiles...) }
func (properties *Properties) DefaultProfiles() []string {
	return append([]string{}, properties.defaults...)
}

var errPropertyResolution = errors.New("配置属性或占位符解析失败")
var profileNamePattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._+@-]*[A-Za-z0-9]$|^[A-Za-z0-9]$`)

// LoadBaselineProperties覆盖仓库现有七个YAML/十七个文档的ConfigData行为。
// 未验收的任意外部位置、导入和profile分组显式拒绝，不能静默忽略。
func LoadBaselineProperties(options PropertyOptions) (*Properties, error) {
	for _, source := range []map[string]string{options.Overrides, options.System, options.DotEnv} {
		for key, value := range source {
			if !utf8.ValidString(key) || !utf8.ValidString(value) {
				return nil, errPropertyResolution
			}
			uniform := strings.ToLower(strings.NewReplacer("_", ".", "-", "").Replace(key))
			if strings.HasPrefix(uniform, "spring.config.") || strings.HasPrefix(uniform, "spring.profiles.group.") {
				return nil, errors.New("尚未验收外部ConfigData位置、导入或profile分组")
			}
		}
	}
	var bundle struct {
		Documents []struct {
			File       string            `json:"file"`
			Index      int               `json:"index"`
			Properties map[string]string `json:"properties"`
		} `json:"documents"`
	}
	if err := json.Unmarshal([]byte(baselineData), &bundle); err != nil {
		return nil, errors.New("内置基线配置无效")
	}
	result := &Properties{profiles: []string{}, defaults: []string{"default"}}
	result.sources = []BindingSource{{Values: maps.Clone(options.Overrides)}, {Environment: true, Values: maps.Clone(options.System)}}
	// ConfigData先于原DotEnv处理器执行：激活文件只能由此时可见的来源决定。
	active, found, err := result.Get("spring.profiles.active")
	if err != nil {
		return nil, err
	}
	if found {
		result.profiles, err = configProfiles(active, false)
		if err != nil {
			return nil, err
		}
	}
	defaults, found, err := result.Get("spring.profiles.default")
	if err != nil {
		return nil, err
	}
	if found {
		result.defaults, err = configProfiles(defaults, false)
		if err != nil {
			return nil, err
		}
	}
	include, found, err := result.Get("spring.profiles.include")
	if err != nil {
		return nil, err
	}
	if found {
		included, err := configProfiles(include, false)
		if err != nil {
			return nil, err
		}
		result.profiles = orderedUnique(append(included, result.profiles...))
	}
	loaded := result.profiles
	if len(loaded) == 0 {
		loaded = result.defaults
	}
	// 后激活的profile文件和后出现的文档优先，且profile文件始终高于基础文件。
	files := []string{"application.yml"}
	for _, profile := range loaded {
		files = append(files, "application-"+profile+".yml")
	}
	for _, file := range slices.Backward(files) {
		for _, doc := range slices.Backward(bundle.Documents) {
			if doc.File != file {
				continue
			}
			if condition, ok := doc.Properties["spring.config.activate.on-profile"]; ok && !slices.Contains(loaded, condition) {
				continue
			}
			result.sources = append(result.sources, BindingSource{Values: doc.Properties})
		}
	}
	result.sources = append(result.sources, BindingSource{Environment: true, Values: maps.Clone(options.DotEnv)})
	// Spring Environment在空active集合时会延迟读取刚加入的dotenv；这不重新执行ConfigData。
	if len(result.profiles) == 0 {
		if text, ok, err := result.Get("spring.profiles.active"); err != nil {
			return nil, err
		} else if ok {
			result.profiles, err = configProfiles(text, true)
			if err != nil {
				return nil, err
			}
		}
	}
	if slices.Equal(result.defaults, []string{"default"}) {
		if text, ok, err := result.Get("spring.profiles.default"); err != nil {
			return nil, err
		} else if ok {
			result.defaults, err = configProfiles(text, true)
			if err != nil {
				return nil, err
			}
		}
	}
	return result, nil
}

func configProfiles(text string, lazy bool) ([]string, error) {
	if lazy {
		text = strings.Map(func(r rune) rune {
			if javaWhitespace(r) {
				return -1
			}
			return r
		}, text)
	}
	if text == "" {
		return []string{}, nil
	}
	result := []string{}
	for _, part := range strings.Split(text, ",") {
		profile := strings.TrimFunc(part, func(r rune) bool { return r <= 0x20 })
		if lazy {
			if profile == "" || strings.HasPrefix(profile, "!") {
				return nil, errPropertyResolution
			}
		} else if !profileNamePattern.MatchString(profile) {
			return nil, errPropertyResolution
		}
		if !slices.Contains(result, profile) {
			result = append(result, profile)
		}
	}
	return result, nil
}
func orderedUnique(values []string) []string {
	result := []string{}
	for _, value := range values {
		if !slices.Contains(result, value) {
			result = append(result, value)
		}
	}
	return result
}

// Get保持缺省与显式空字符串的区别；错误不回显值、路径或任意动态键。
func (properties *Properties) Get(key string) (string, bool, error) {
	text, found := properties.raw(key)
	if !found {
		return "", false, nil
	}
	resolved, err := properties.expand(text, map[string]bool{key: true}, 0)
	if err != nil {
		return "", true, err
	}
	return resolved, true, nil
}
func (properties *Properties) raw(key string) (string, bool) {
	for _, source := range properties.sources {
		if source.Environment {
			// Boot canonical映射移除字段名内连字符；原SystemEnvironment还接受点/横线到下划线的别名。
			if canonicalPropertyKey(key) {
				mapped := strings.ToUpper(strings.NewReplacer("-", "", ".", "_", "[", "_", "]", "").Replace(key))
				if value, ok := source.Values[mapped]; ok {
					return value, true
				}
			}
			for _, name := range environmentAliases(key) {
				if value, ok := source.Values[name]; ok {
					return value, true
				}
			}
		} else if value, ok := source.Values[key]; ok {
			return value, true
		}
	}
	return "", false
}
func canonicalPropertyKey(key string) bool {
	if key == "" {
		return false
	}
	for _, r := range key {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' || r == '-' || r == '[' || r == ']') {
			return false
		}
	}
	return true
}
func environmentAliases(key string) []string {
	values := []string{key, strings.ReplaceAll(key, ".", "_"), strings.ReplaceAll(key, "-", "_"), strings.NewReplacer(".", "_", "-", "_").Replace(key)}
	for _, value := range slices.Clone(values) {
		values = append(values, strings.ToUpper(value))
	}
	return orderedUnique(values)
}

// expand实现原配置所用${key:default}、嵌套key/default和Spring反斜线转义。
// 限制深度/输出防止循环或指数膨胀；不执行SpEL、shell或外部读取。
func (properties *Properties) expand(text string, visited map[string]bool, depth int) (string, error) {
	if depth > 64 || len(text) > 1<<20 {
		return "", errPropertyResolution
	}
	var result strings.Builder
	for len(text) > 0 {
		index := strings.Index(text, "${")
		if index < 0 {
			result.WriteString(text)
			break
		}
		if index > 0 && text[index-1] == '\\' {
			result.WriteString(text[:index-1])
			result.WriteString("${")
			text = text[index+2:]
			continue
		}
		end, nesting, colon := index+2, 1, -1
		for ; end < len(text); end++ {
			if strings.HasPrefix(text[end:], "${") {
				nesting++
				end++
				continue
			}
			if text[end] == '}' {
				nesting--
				if nesting == 0 {
					break
				}
			}
			if text[end] == ':' && nesting == 1 && colon < 0 {
				colon = end
			}
		}
		if nesting != 0 {
			result.WriteString(text)
			break
		}
		result.WriteString(text[:index])
		keyText := text[index+2 : end]
		if colon >= 0 {
			keyText = text[index+2 : colon]
		}
		key, err := properties.expand(keyText, visited, depth+1)
		if err != nil {
			return "", err
		}
		value, found := properties.raw(key)
		if !found {
			if colon < 0 {
				return "", errPropertyResolution
			}
			value, err = properties.expand(text[colon+1:end], visited, depth+1)
		} else {
			if visited[key] {
				return "", errPropertyResolution
			}
			visited[key] = true
			value, err = properties.expand(value, visited, depth+1)
			delete(visited, key)
		}
		if err != nil {
			return "", err
		}
		if result.Len()+len(value) > 1<<20 {
			return "", errPropertyResolution
		}
		result.WriteString(value)
		text = text[end+1:]
	}
	if result.Len() > 1<<20 {
		return "", errPropertyResolution
	}
	return result.String(), nil
}
