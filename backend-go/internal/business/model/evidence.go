package model

import (
	"errors"
	"math"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

// WindowEvidence 只记录当前运行代的被动证据，成功输入是下界，不能当作模型上限。
type WindowSnapshot struct {
	ContextTokens, InputTokens *int
	ExplicitInputLimit         bool
	VerifiedInputTokens        int
	TokenRatio                 float64
}
type WindowEvidence struct {
	mu     sync.Mutex
	epoch  uint64
	values map[string]WindowSnapshot
}

func NewWindowEvidence() *WindowEvidence     { return &WindowEvidence{values: map[string]WindowSnapshot{}} }
func (e *WindowEvidence) Generation() uint64 { e.mu.Lock(); defer e.mu.Unlock(); return e.epoch }
func (e *WindowEvidence) Reset() {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.epoch++
	e.values = map[string]WindowSnapshot{}
}
func (e *WindowEvidence) get(name string) WindowSnapshot {
	v, ok := e.values[name]
	if !ok {
		v.TokenRatio = 1
	}
	v.ContextTokens = copyPointer(v.ContextTokens)
	v.InputTokens = copyPointer(v.InputTokens)
	return v
}
func (e *WindowEvidence) Snapshot(epoch uint64, name string) WindowSnapshot {
	e.mu.Lock()
	defer e.mu.Unlock()
	if epoch != e.epoch {
		return WindowSnapshot{TokenRatio: 1}
	}
	return e.get(name)
}
func (e *WindowEvidence) put(name string, v WindowSnapshot) {
	if _, ok := e.values[name]; !ok && len(e.values) >= 256 {
		e.values = map[string]WindowSnapshot{}
	}
	e.values[name] = v
}
func (e *WindowEvidence) Accepted(epoch uint64, name string, estimated, actual int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if epoch != e.epoch || actual <= 0 || actual > 32*1024*1024 {
		return
	}
	v := e.get(name)
	ratio := 1.0
	if estimated >= 256 {
		ratio = math.Max(1, float64(actual)/float64(estimated))
	}
	v.TokenRatio = math.Max(v.TokenRatio, ratio)
	v.VerifiedInputTokens = max(v.VerifiedInputTokens, actual)
	e.put(name, v)
}
func minimum(a, b *int) *int {
	if a == nil {
		return copyPointer(b)
	}
	if b == nil {
		return copyPointer(a)
	}
	v := min(*a, *b)
	return &v
}

var contextLimit = regexp.MustCompile(`(?i)(?:maximum\s+context\s+length|context\s+window\s+(?:limit|maximum)|max(?:imum)?[_ ]context[_ ](?:length|window))[^0-9]{0,32}([0-9][0-9,]*)`)
var inputLimits = []*regexp.Regexp{regexp.MustCompile(`(?i)input\s+length\s+should\s+be\s*[^0-9]{0,8}1\s*,\s*([0-9][0-9,]*)`), regexp.MustCompile(`(?i)(?:max_input_tokens|maximum\s+input\s+(?:length|tokens)|input\s+token\s+limit)[^0-9]{0,32}([0-9][0-9,]*)`), regexp.MustCompile(`(?i)prompt\s+is\s+too\s+long[^\n]{0,80}>\s*([0-9][0-9,]*)\s*(?:maximum|tokens|token)`)}

func errorTexts(err error) []string {
	out := []string{}
	for depth := 0; err != nil && depth < 64; depth++ {
		if e, ok := err.(*UpstreamError); ok {
			out = append(out, e.body)
		} else {
			out = append(out, err.Error())
		}
		err = errors.Unwrap(err)
	}
	return out
}
func IsOverflow(err error) bool {
	for _, s := range errorTexts(err) {
		s = strings.ToLower(s)
		for _, p := range []string{"context_length_exceeded", "maximum context length", "context window exceeded", "prompt is too long", "input is too long", "input length should be", "exceeds the context window", "too many input tokens"} {
			if strings.Contains(s, p) {
				return true
			}
		}
		if strings.Contains(s, "max_input_tokens") && strings.Contains(s, "exceed") {
			return true
		}
	}
	return false
}
func readLimit(re *regexp.Regexp, s string) *int {
	var result *int
	for _, m := range re.FindAllStringSubmatch(s, -1) {
		n, err := strconv.Atoi(strings.ReplaceAll(m[1], ",", ""))
		if err == nil && n >= 1024 && n <= 32*1024*1024 {
			result = minimum(result, &n)
		}
	}
	return result
}
func LimitsFrom(err error) (context, input *int) {
	for _, s := range errorTexts(err) {
		context = minimum(context, readLimit(contextLimit, s))
		for _, re := range inputLimits {
			input = minimum(input, readLimit(re, s))
		}
	}
	return
}
func (e *WindowEvidence) Rejected(epoch uint64, name string, err error, attempted int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if epoch != e.epoch || !IsOverflow(err) {
		return
	}
	context, input := LimitsFrom(err)
	v := e.get(name)
	v.ContextTokens = minimum(v.ContextTokens, context)
	if input != nil {
		previous := copyPointer(v.InputTokens)
		v.InputTokens = minimum(v.InputTokens, input)
		v.ExplicitInputLimit = *v.InputTokens == *input || v.ExplicitInputLimit && previous != nil && *previous == *v.InputTokens
	} else if context == nil && attempted >= 1024 && attempted > v.VerifiedInputTokens {
		reduced := max(max(1024, v.VerifiedInputTokens+512), int(float64(attempted)*0.65))
		previous := copyPointer(v.InputTokens)
		v.InputTokens = minimum(v.InputTokens, &reduced)
		if previous == nil || *previous != *v.InputTokens {
			v.ExplicitInputLimit = false
		}
	}
	e.put(name, v)
}
