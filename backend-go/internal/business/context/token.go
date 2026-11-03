// Package chatcontext 管理对话预算、模型输入保护和无副作用的计数。
package chatcontext

import (
	"bufio"
	"compress/gzip"
	"container/heap"
	"embed"
	"encoding/base64"
	"encoding/json"
	"math"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"unicode/utf16"
	"unicode/utf8"

	"personal-ai-os/backend-go/internal/business/model"
)

//go:embed assets/*.txt.gz
var vocabularies embed.FS

func JavaLength(text string) int { return len(utf16.Encode([]rune(text))) }

// JavaSubstring 保留原 UTF-16 截断落盘时孤立代理项编码为问号的行为。
func JavaSubstring(text string, start, end int) string {
	units := utf16.Encode([]rune(text))
	start, end = max(0, min(start, len(units))), max(0, min(end, len(units)))
	if start >= end {
		return ""
	}
	part := append([]uint16(nil), units[start:end]...)
	if part[0] >= 0xdc00 && part[0] <= 0xdfff {
		part[0] = '?'
	}
	if part[len(part)-1] >= 0xd800 && part[len(part)-1] <= 0xdbff {
		part[len(part)-1] = '?'
	}
	return string(utf16.Decode(part))
}

func BudgetTokens(chars int) int { return max(0, int(math.Floor(float64(chars)/1.6+0.5))) }
func ContentTokens(text string) int {
	return ContentTokensFor(text, JavaLength(text))
}

// ContentTokensFor 用文本判断口径，再对整份提示的字符数折算，与 Java StreamUsage 一致。
func ContentTokensFor(text string, chars int) int {
	if chars <= 0 {
		return 0
	}
	if text == "" {
		return BudgetTokens(chars)
	}
	n, nonASCII := 0, 0
	for _, r := range text {
		n++
		if r > 127 {
			nonASCII++
		}
	}
	ratio := 5.5
	if float64(nonASCII)/float64(n) >= .25 {
		ratio = 1.6
	}
	return max(0, int(math.Floor(float64(chars)/ratio+0.5)))
}

type vocabulary struct {
	once  sync.Once
	ranks map[string]int
}

var tables = map[string]*vocabulary{"cl100k_base": {}, "o200k_base": {}, "r50k_base": {}, "p50k_base": {}}

func ranksFor(name string) map[string]int {
	table := tables[name]
	table.once.Do(func() {
		file, err := vocabularies.Open("assets/" + name + ".txt.gz")
		if err != nil {
			panic("内置BPE词表缺失")
		}
		defer file.Close()
		reader, err := gzip.NewReader(file)
		if err != nil {
			panic("内置BPE词表损坏")
		}
		defer reader.Close()
		table.ranks = map[string]int{}
		scanner := bufio.NewScanner(reader)
		for scanner.Scan() {
			parts := strings.Split(scanner.Text(), " ")
			if len(parts) != 2 {
				panic("内置BPE词表格式错误")
			}
			key, e1 := base64.StdEncoding.DecodeString(parts[0])
			rank, e2 := strconv.Atoi(parts[1])
			if e1 != nil || e2 != nil {
				panic("内置BPE词表格式错误")
			}
			table.ranks[string(key)] = rank
		}
		if scanner.Err() != nil {
			panic("内置BPE词表读取失败")
		}
	})
	return table.ranks
}

func encodingFor(name string) (string, bool) {
	for _, prefix := range []string{"gpt-4o"} {
		if strings.HasPrefix(name, prefix) {
			return "o200k_base", true
		}
	}
	for _, prefix := range []string{"gpt-4", "gpt-3.5-turbo"} {
		if strings.HasPrefix(name, prefix) {
			return "cl100k_base", true
		}
	}
	switch name {
	case "text-embedding-ada-002", "text-embedding-3-small", "text-embedding-3-large":
		return "cl100k_base", true
	case "text-davinci-003", "text-davinci-002", "code-davinci-002", "code-davinci-001", "code-cushman-002", "code-cushman-001", "davinci-codex", "cushman-codex", "text-davinci-edit-001", "code-davinci-edit-001":
		return "p50k_base", true
	case "text-davinci-001", "text-curie-001", "text-babbage-001", "text-ada-001", "davinci", "curie", "babbage", "ada", "text-similarity-davinci-001", "text-similarity-curie-001", "text-similarity-babbage-001", "text-similarity-ada-001", "text-search-davinci-doc-001", "text-search-curie-doc-001", "text-search-babbage-doc-001", "text-search-ada-doc-001", "code-search-babbage-code-001", "code-search-ada-code-001":
		return "r50k_base", true
	}
	return "cl100k_base", false
}

const white = `\p{Z}\t\n\v\f\r\x{0085}`

var patterns = map[string][]*regexp.Regexp{}

func init() {
	groups := map[string][]string{
		"cl100k_base": {`(?i:'s|'t|'re|'ve|'m|'ll|'d)`, `[^\r\n\p{L}\p{N}]?\p{L}+`, `\p{N}{1,3}`, ` ?[^` + white + `\p{L}\p{N}]+[\r\n]*`, `[` + white + `]*[\r\n]+`},
		"o200k_base":  {`[^\r\n\p{L}\p{N}]?[\p{Lu}\p{Lt}\p{Lm}\p{Lo}\p{M}]*[\p{Ll}\p{Lm}\p{Lo}\p{M}]+(?i:'s|'t|'re|'ve|'m|'ll|'d)?`, `[^\r\n\p{L}\p{N}]?[\p{Lu}\p{Lt}\p{Lm}\p{Lo}\p{M}]+[\p{Ll}\p{Lm}\p{Lo}\p{M}]*(?i:'s|'t|'re|'ve|'m|'ll|'d)?`, `\p{N}{1,3}`, ` ?[^` + white + `\p{L}\p{N}]+[\r\n/]*`, `[` + white + `]*[\r\n]+`},
		"r50k_base":   {`'(?:[sdmt]|ll|ve|re)`, ` ?\p{L}+`, ` ?\p{N}+`, ` ?[^` + white + `\p{L}\p{N}]+`},
	}
	groups["p50k_base"] = groups["r50k_base"]
	for name, expressions := range groups {
		for _, expression := range expressions {
			patterns[name] = append(patterns[name], regexp.MustCompile("^(?:"+expression+")"))
		}
	}
}

var whitespace = regexp.MustCompile(`^[` + white + `]+`)

func pieceLength(text, encoding string) int {
	for _, pattern := range patterns[encoding] {
		if match := pattern.FindStringIndex(text); match != nil {
			return match[1]
		}
	}
	if match := whitespace.FindStringIndex(text); match != nil {
		n := match[1]
		// \s+(?!\S) 可回退一个字符，把单个前导空白留给下一词。
		if n < len(text) {
			_, size := utf8.DecodeLastRuneInString(text[:n])
			if n > size {
				return n - size
			}
		}
		return n
	}
	_, size := utf8.DecodeRuneInString(text)
	return size
}

type pair struct{ rank, left, right, lv, rv int }
type pairs []pair

func (h pairs) Len() int { return len(h) }
func (h pairs) Less(i, j int) bool {
	if h[i].rank == h[j].rank {
		return h[i].left < h[j].left
	}
	return h[i].rank < h[j].rank
}
func (h pairs) Swap(i, j int) { h[i], h[j] = h[j], h[i] }
func (h *pairs) Push(x any)   { *h = append(*h, x.(pair)) }
func (h *pairs) Pop() any     { old := *h; x := old[len(old)-1]; *h = old[:len(old)-1]; return x }

type node struct {
	start, end, prev, next, version int
	dead                            bool
}

// 使用有版本的相邻对堆，超长单词也不退化成逐轮扫描全部字节的平方开销。
func pieceTokens(text string, ranks map[string]int) int {
	if text == "" {
		return 0
	}
	if _, ok := ranks[text]; ok {
		return 1
	}
	nodes := make([]node, len(text))
	h := &pairs{}
	for i := range nodes {
		nodes[i] = node{start: i, end: i + 1, prev: i - 1, next: i + 1}
	}
	nodes[len(nodes)-1].next = -1
	add := func(left int) {
		if left < 0 || nodes[left].dead {
			return
		}
		right := nodes[left].next
		if right < 0 || nodes[right].dead {
			return
		}
		if rank, ok := ranks[text[nodes[left].start:nodes[right].end]]; ok {
			heap.Push(h, pair{rank, left, right, nodes[left].version, nodes[right].version})
		}
	}
	for i := range nodes {
		add(i)
	}
	count := len(nodes)
	for h.Len() > 0 {
		p := heap.Pop(h).(pair)
		a, b := &nodes[p.left], &nodes[p.right]
		if a.dead || b.dead || a.next != p.right || a.version != p.lv || b.version != p.rv {
			continue
		}
		a.end = b.end
		a.next = b.next
		a.version++
		b.dead = true
		b.version++
		if a.next >= 0 {
			nodes[a.next].prev = p.left
		}
		count--
		add(a.prev)
		add(p.left)
	}
	return count
}

func CountText(text, modelName string) int {
	if text == "" {
		return 0
	}
	original := text
	encoding, known := encodingFor(modelName)
	ranks := ranksFor(encoding)
	total := 0
	for text != "" {
		n := pieceLength(text, encoding)
		total += pieceTokens(text[:n], ranks)
		text = text[n:]
	}
	if !known {
		return max(total, ContentTokens(original))
	}
	return total
}

func CountPrompt(request model.ChatRequest, modelName string, imageReserve int) int {
	total := int64(32)
	for _, message := range request.Messages {
		total += int64(12 + CountText(message.Content, modelName) + len(message.Images)*imageReserve)
		for _, call := range message.ToolCalls {
			total += int64(12 + CountText(call.ID, modelName) + CountText(call.Function.Name, modelName) + CountText(call.Function.Arguments, modelName))
		}
		if message.Role == "tool" {
			total += int64(12 + CountText(message.ToolCallID, modelName) + CountText(message.Name, modelName))
		}
	}
	for _, tool := range request.Tools {
		raw, _ := json.Marshal(tool.Parameters)
		total += int64(16 + CountText(tool.Name, modelName) + CountText(tool.Description, modelName) + CountText(string(raw), modelName))
	}
	return int(min(int64(math.MaxInt32), total))
}

func CompactSchemaChars(text string) int {
	count, inString, escaped := 0, false, false
	for _, r := range text {
		units := 1
		if r > 0xffff {
			units = 2
		}
		if inString {
			count += units
			if escaped {
				escaped = false
			} else if r == '\\' {
				escaped = true
			} else if r == '"' {
				inString = false
			}
			continue
		}
		if r == '"' {
			inString = true
			count++
		} else if r != ' ' && r != '\n' && r != '\r' && r != '\t' {
			count += units
		}
	}
	return count
}
