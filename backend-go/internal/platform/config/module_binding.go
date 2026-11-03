package config

import (
	"math"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"golang.org/x/text/cases"
	"golang.org/x/text/language"
)

// 本文件只绑定已经解析的canonical字符串属性，不加载来源或启动对应业务模块。
// 零值、注解默认值与紧凑构造器归一化分别处理，不能套用YAML默认值。
type PlanProperties struct {
	Enabled        bool `json:"enabled"`
	MaxInjectChars int  `json:"maxInjectChars"`
}

type CalendarProperties struct {
	ScanEnabled         bool          `json:"scanEnabled"`
	ScanIntervalMS      int64         `json:"scanIntervalMs"`
	ScanInitialDelayMS  int64         `json:"scanInitialDelayMs"`
	ScanLookahead       time.Duration `json:"scanLookahead"`
	ScanLookback        time.Duration `json:"scanLookback"`
	DefaultReminderLead time.Duration `json:"defaultReminderLead"`
	MaxRangeDays        int           `json:"maxRangeDays"`
	DefaultQueryLimit   int           `json:"defaultQueryLimit"`
	MaxQueryLimit       int           `json:"maxQueryLimit"`
}

const MaxReminderLeadMinutes = 10080

func (properties CalendarProperties) EffectiveScanLookahead() time.Duration {
	return max(properties.ScanLookahead, MaxReminderLeadMinutes*time.Minute)
}
func (properties CalendarProperties) NormalizeRangeDays(requested *int) int {
	if requested == nil || *requested <= 0 {
		return min(7, properties.MaxRangeDays)
	}
	return min(*requested, properties.MaxRangeDays)
}
func (properties CalendarProperties) NormalizeLimit(requested *int) int {
	if requested == nil || *requested <= 0 {
		return properties.DefaultQueryLimit
	}
	return min(*requested, properties.MaxQueryLimit)
}

type AIMemory struct {
	MaxMessages         int `json:"maxMessages"`
	ContextWindowTokens int `json:"contextWindowTokens"`
}
type AIProperties struct {
	Memory       AIMemory `json:"memory"`
	systemPrompt string
}

func (properties AIProperties) SystemPrompt() string { return properties.systemPrompt }
func (AIProperties) String() string                  { return "AIProperties{redacted}" }
func (properties AIProperties) GoString() string     { return properties.String() }

type ContextSummary struct {
	Enabled         bool          `json:"enabled"`
	MinMessages     int           `json:"minMessages"`
	MaxExcerptChars int           `json:"maxExcerptChars"`
	MaxSummaryChars int           `json:"maxSummaryChars"`
	Timeout         time.Duration `json:"timeout"`
}
type ContextAutoMemory struct {
	Enabled  bool `json:"enabled"`
	MaxItems int  `json:"maxItems"`
}
type ContextProperties struct {
	Summary    ContextSummary    `json:"summary"`
	AutoMemory ContextAutoMemory `json:"autoMemory"`
}
type ContextProtectionProperties struct {
	OutputReserveTokens int `json:"outputReserveTokens"`
	SafetyMarginTokens  int `json:"safetyMarginTokens"`
	ImageReserveTokens  int `json:"imageReserveTokens"`
	MaxRetries          int `json:"maxRetries"`
}

func DefaultContextProtectionProperties() ContextProtectionProperties {
	return ContextProtectionProperties{4096, 512, 4096, 1}
}

type AppProperties struct{ name, version *string }

func (properties AppProperties) Name() *string     { return stringCopy(properties.name) }
func (properties AppProperties) Version() *string  { return stringCopy(properties.version) }
func (AppProperties) String() string               { return "AppProperties{redacted}" }
func (properties AppProperties) GoString() string  { return properties.String() }
func (AppProperties) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

type KnowledgeProperties struct {
	EmbeddingDimensions  int     `json:"embeddingDimensions"`
	TopK                 int     `json:"topK"`
	SimilarityThreshold  float64 `json:"similarityThreshold"`
	MaxFileSizeBytes     int64   `json:"maxFileSizeBytes"`
	allowedExtensions    []string
	MinChunkSizeChars    int     `json:"minChunkSizeChars"`
	ChunkSizeChars       int     `json:"chunkSizeChars"`
	ChunkOverlapChars    int     `json:"chunkOverlapChars"`
	HybridSearchEnabled  bool    `json:"hybridSearchEnabled"`
	RRFK                 int     `json:"rrfK"`
	KeywordChannelWeight float64 `json:"keywordChannelWeight"`
}

func (properties KnowledgeProperties) AllowedExtensions() []string {
	return slices.Clone(properties.allowedExtensions)
}
func (properties KnowledgeProperties) NormalizeTopK(requested *int) int {
	if requested == nil || *requested <= 0 {
		return properties.TopK
	}
	return min(*requested, 20)
}

// 保持Java strip与完整Unicode小写规则；配置本身不trim、不去重。
func (properties KnowledgeProperties) IsExtensionAllowed(extension string) bool {
	if javaStrip(extension) == "" {
		return false
	}
	lower := cases.Lower(language.Und)
	normalized := lower.String(javaStrip(extension))
	return slices.ContainsFunc(properties.allowedExtensions, func(allowed string) bool { return lower.String(allowed) == normalized })
}

type MemoryRanking struct {
	Enabled      bool          `json:"enabled"`
	Timeout      time.Duration `json:"timeout"`
	HalfLifeDays int           `json:"halfLifeDays"`
}
type MemoryBackfill struct {
	Enabled   bool `json:"enabled"`
	BatchSize int  `json:"batchSize"`
}
type MemoryProperties struct {
	PinnedMaxCount   int            `json:"pinnedMaxCount"`
	SimilarThreshold float64        `json:"similarThreshold"`
	Ranking          MemoryRanking  `json:"ranking"`
	Backfill         MemoryBackfill `json:"backfill"`
}

func BindPlanProperties(properties map[string]string) (PlanProperties, error) {
	r := recordReader{properties: properties}
	value := PlanProperties{r.boolean("app.plan.enabled", false), normalizedPositive(r.integer("app.plan.max-inject-chars"), 1200)}
	if r.err != nil {
		return PlanProperties{}, r.err
	}
	return value, nil
}

func BindCalendarProperties(properties map[string]string) (CalendarProperties, error) {
	r := recordReader{properties: properties}
	value := CalendarProperties{
		ScanEnabled:         r.boolean("app.calendar.scan-enabled", false),
		ScanIntervalMS:      r.long("app.calendar.scan-interval-ms"),
		ScanInitialDelayMS:  r.long("app.calendar.scan-initial-delay-ms"),
		ScanLookahead:       r.duration("app.calendar.scan-lookahead", 24*time.Hour, false),
		ScanLookback:        r.duration("app.calendar.scan-lookback", 24*time.Hour, false),
		DefaultReminderLead: r.duration("app.calendar.default-reminder-lead", 15*time.Minute, true),
		MaxRangeDays:        normalizedPositive(r.integer("app.calendar.max-range-days"), 90),
		DefaultQueryLimit:   normalizedPositive(r.integer("app.calendar.default-query-limit"), 100),
		MaxQueryLimit:       r.integer("app.calendar.max-query-limit"),
	}
	if value.ScanIntervalMS <= 0 {
		value.ScanIntervalMS = 10000
	}
	if value.ScanInitialDelayMS < 0 {
		value.ScanInitialDelayMS = 10000
	}
	if value.MaxQueryLimit <= 0 || value.MaxQueryLimit > 500 {
		value.MaxQueryLimit = 500
	}
	if r.err != nil {
		return CalendarProperties{}, r.err
	}
	return value, nil
}

func BindAIProperties(properties map[string]string) (AIProperties, error) {
	r := recordReader{properties: properties}
	value := AIProperties{Memory: AIMemory{
		normalizedPositive(r.integer("app.ai.memory.max-messages"), 20),
		normalizedPositive(r.integer("app.ai.memory.context-window-tokens"), 131072),
	}}
	if prompt := r.text("app.ai.system-prompt"); prompt != nil {
		value.systemPrompt = *prompt
	}
	if javaStrip(value.systemPrompt) == "" {
		value.systemPrompt = "你是 Personal AI OS 的个人助手。回答简洁、准确，默认使用中文，必要时使用列表。"
	}
	if r.err != nil {
		return AIProperties{}, r.err
	}
	return value, nil
}

func BindContextProperties(properties map[string]string) (ContextProperties, error) {
	r := recordReader{properties: properties}
	// nested完全缺省时由父record创建enabled=true；任一已知子字段实际绑定后primitive缺省false。
	summaryBound := anyProperty(properties, "app.context.summary.", "enabled", "min-messages", "max-excerpt-chars", "max-summary-chars", "timeout")
	value := ContextProperties{
		Summary: ContextSummary{
			Enabled:         r.boolean("app.context.summary.enabled", !summaryBound),
			MinMessages:     normalizedPositive(r.integer("app.context.summary.min-messages"), 4),
			MaxExcerptChars: normalizedPositive(r.integer("app.context.summary.max-excerpt-chars"), 4000),
			MaxSummaryChars: normalizedPositive(r.integer("app.context.summary.max-summary-chars"), 1200),
			Timeout:         r.duration("app.context.summary.timeout", 30*time.Second, false),
		},
		AutoMemory: ContextAutoMemory{
			Enabled:  r.boolean("app.context.auto-memory.enabled", false),
			MaxItems: normalizedPositive(r.integer("app.context.auto-memory.max-items"), 3),
		},
	}
	if r.err != nil {
		return ContextProperties{}, r.err
	}
	return value, nil
}

func BindContextProtectionProperties(properties map[string]string) (ContextProtectionProperties, error) {
	r := recordReader{properties: properties}
	value := ContextProtectionProperties{
		OutputReserveTokens: normalizedPositive(r.integer("app.context.protection.output-reserve-tokens"), 4096),
		SafetyMarginTokens:  normalizedPositive(r.integer("app.context.protection.safety-margin-tokens"), 512),
		ImageReserveTokens:  normalizedPositive(r.integer("app.context.protection.image-reserve-tokens"), 4096),
		MaxRetries:          max(0, min(r.integer("app.context.protection.max-retries"), 2)),
	}
	if r.err != nil {
		return ContextProtectionProperties{}, r.err
	}
	return value, nil
}

func BindAppProperties(properties map[string]string) (AppProperties, error) {
	r := recordReader{properties: properties}
	value := AppProperties{r.text("app.name"), r.text("app.version")}
	if r.err != nil {
		return AppProperties{}, r.err
	}
	return value, nil
}

func BindKnowledgeProperties(properties map[string]string) (KnowledgeProperties, error) {
	r := recordReader{properties: properties}
	value := KnowledgeProperties{
		EmbeddingDimensions:  normalizedPositive(r.integer("app.knowledge.embedding-dimensions"), 1024),
		TopK:                 min(normalizedPositive(r.integer("app.knowledge.top-k"), 4), 20),
		SimilarityThreshold:  r.number("app.knowledge.similarity-threshold"),
		MaxFileSizeBytes:     r.long("app.knowledge.max-file-size-bytes"),
		MinChunkSizeChars:    normalizedPositive(r.integer("app.knowledge.min-chunk-size-chars"), 350),
		ChunkSizeChars:       normalizedPositive(r.integer("app.knowledge.chunk-size-chars"), 1200),
		ChunkOverlapChars:    max(0, r.integer("app.knowledge.chunk-overlap-chars")),
		HybridSearchEnabled:  r.boolean("app.knowledge.hybrid-search-enabled", false),
		RRFK:                 normalizedPositive(r.integer("app.knowledge.rrf-k"), 60),
		KeywordChannelWeight: r.number("app.knowledge.keyword-channel-weight"),
	}
	value.allowedExtensions = r.list("app.knowledge.allowed-extensions")
	if len(value.allowedExtensions) == 0 {
		value.allowedExtensions = []string{"md", "markdown", "txt"}
	}
	if value.SimilarityThreshold < 0 || value.SimilarityThreshold > 1 {
		value.SimilarityThreshold = 0.3
	}
	if value.MaxFileSizeBytes <= 0 {
		value.MaxFileSizeBytes = 5 * 1024 * 1024
	}
	if value.KeywordChannelWeight <= 0 || value.KeywordChannelWeight > 1 {
		value.KeywordChannelWeight = 1
	}
	if r.err != nil {
		return KnowledgeProperties{}, r.err
	}
	return value, nil
}

func BindMemoryProperties(properties map[string]string) (MemoryProperties, error) {
	r := recordReader{properties: properties}
	rankingBound := anyProperty(properties, "app.memory.ranking.", "enabled", "timeout", "half-life-days")
	backfillBound := anyProperty(properties, "app.memory.backfill.", "enabled", "batch-size")
	value := MemoryProperties{
		PinnedMaxCount:   normalizedPositive(r.integer("app.memory.pinned-max-count"), 10),
		SimilarThreshold: r.number("app.memory.similar-threshold"),
		Ranking: MemoryRanking{
			Enabled:      r.boolean("app.memory.ranking.enabled", !rankingBound),
			Timeout:      r.duration("app.memory.ranking.timeout", 400*time.Millisecond, false),
			HalfLifeDays: r.integer("app.memory.ranking.half-life-days"),
		},
		Backfill: MemoryBackfill{
			Enabled:   r.boolean("app.memory.backfill.enabled", !backfillBound),
			BatchSize: normalizedPositive(r.integer("app.memory.backfill.batch-size"), 20),
		},
	}
	if value.SimilarThreshold <= 0 || value.SimilarThreshold > 1 {
		value.SimilarThreshold = 0.92
	}
	if value.Ranking.HalfLifeDays < 0 {
		value.Ranking.HalfLifeDays = 30
	}
	if r.err != nil {
		return MemoryProperties{}, r.err
	}
	return value, nil
}

func anyProperty(properties map[string]string, prefix string, names ...string) bool {
	for _, name := range names {
		if value, found := properties[prefix+name]; found && !(name == "timeout" && value == "") {
			return true
		}
	}
	return false
}
func normalizedPositive(value, fallback int) int {
	if value <= 0 {
		return fallback
	}
	return value
}
func stringCopy(value *string) *string {
	if value == nil {
		return nil
	}
	copy := *value
	return &copy
}

// recordReader只记录首个安全错误，不保存或格式化原始输入。
type recordReader struct {
	properties map[string]string
	err        error
}

func (reader *recordReader) fail(key string, cause error) {
	if reader.err == nil {
		reader.err = &propertyError{key, cause}
	}
}
func (reader *recordReader) boolean(key string, fallback bool) bool {
	value, err := bindBoolean(reader.properties, key, fallback)
	if err != nil {
		reader.fail(key, ErrInvalidProperty)
	}
	return value
}
func (reader *recordReader) integer(key string) int { return int(reader.signed(key, 32)) }
func (reader *recordReader) long(key string) int64  { return reader.signed(key, 64) }
func (reader *recordReader) signed(key string, bits int) int64 {
	text, found := reader.properties[key]
	if !found {
		return 0
	}
	value, err := bindJavaInteger(text, key, bits)
	if err != nil {
		reader.fail(key, ErrInvalidProperty)
	}
	return value
}
func (reader *recordReader) text(key string) *string {
	text, found := reader.properties[key]
	if !found {
		return nil
	}
	if !utf8.ValidString(text) {
		reader.fail(key, ErrInvalidProperty)
		return nil
	}
	return &text
}
func (reader *recordReader) duration(key string, fallback time.Duration, allowZero bool) time.Duration {
	text, found := reader.properties[key]
	if !found {
		return fallback
	}
	value, err := durationNanos(text)
	if err != nil {
		reader.fail(key, ErrInvalidProperty)
		return 0
	}
	if value == nil || value.Sign() < 0 || (value.Sign() == 0 && !allowZero) {
		return fallback
	}
	if !value.IsInt64() {
		reader.fail(key, ErrScalarOverflow)
		return 0
	}
	return time.Duration(value.Int64())
}

var javaDoublePattern = regexp.MustCompile(`^[+-]?(?:(?:NaN|Infinity)|(?:(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?|0[xX](?:[0-9a-fA-F]+(?:\.[0-9a-fA-F]*)?|\.[0-9a-fA-F]+)[pP][+-]?[0-9]+)[fFdD]?)$`)

func (reader *recordReader) number(key string) float64 {
	text, found := reader.properties[key]
	if !found {
		return 0
	}
	text = strings.Map(func(r rune) rune {
		if javaWhitespace(r) {
			return -1
		}
		return r
	}, text)
	if !javaDoublePattern.MatchString(text) {
		reader.fail(key, ErrInvalidProperty)
		return 0
	}
	if strings.TrimLeft(text, "+-") == "NaN" {
		return math.NaN()
	}
	if strings.TrimLeft(text, "+-") == "Infinity" {
		if strings.HasPrefix(text, "-") {
			return math.Inf(-1)
		}
		return math.Inf(1)
	}
	text = strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(strings.TrimSuffix(text, "f"), "F"), "d"), "D")
	value, err := strconv.ParseFloat(text, 64)
	// Double.parseDouble按IEEE溢出到Infinity，随后由record比较归一；不能当解析错误。
	if err != nil && !math.IsInf(value, 0) {
		reader.fail(key, ErrInvalidProperty)
	}
	return value
}
func (reader *recordReader) list(key string) []string {
	input := mcpListInput{}
	if scalar, found := reader.properties[key]; found {
		if !utf8.ValidString(scalar) {
			reader.fail(key, ErrInvalidProperty)
			return nil
		}
		input.scalar = &scalar
	} else {
		for name, text := range reader.properties {
			if !strings.HasPrefix(name, key+"[") {
				continue
			}
			indexText := strings.TrimSuffix(strings.TrimPrefix(name, key+"["), "]")
			index, err := strconv.Atoi(indexText)
			if err != nil || index < 0 || strconv.Itoa(index) != indexText || !utf8.ValidString(text) {
				reader.fail(key, ErrInvalidProperty)
				return nil
			}
			if input.indexed == nil {
				input.indexed = map[int]string{}
			}
			input.indexed[index] = text
		}
	}
	value, err := bindMCPList(input)
	if err != nil {
		reader.fail(key, ErrInvalidProperty)
	}
	return value
}
