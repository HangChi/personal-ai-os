package knowledge

import (
	"regexp"
	"strings"
	"unicode/utf16"
)

var headingPattern = regexp.MustCompile("^(#{1,6})\\s+(.+?)\\s*#*$")
var paragraphPattern = regexp.MustCompile("\n[\t\n\v\f\r ]*\n")

type piece struct {
	path []string
	text string
}

type markdownChunk struct {
	text      string
	protected bool
}
type markdownUnit struct {
	text       string
	protected  bool
	start, end int
}

// SplitMarkdown 保留标题栈、代码围栏、自然断点与短块合并的原始顺序。
func SplitMarkdown(text string, target, minimum, overlap int) []string {
	target = max(target, 1)
	minimum = max(minimum, 1)
	overlap = max(overlap, 0)
	result := []string{}
	if strip(text) == "" {
		return result
	}
	var sections []piece
	var stack [6]string
	var path []string
	var body strings.Builder
	protectedSpans := markdownHeadingProtection(text)
	spanIndex, offset := 0, 0
	flush := func() {
		if strip(body.String()) != "" || len(path) > 0 {
			sections = append(sections, piece{append([]string(nil), path...), body.String()})
		}
		body.Reset()
	}
	for _, line := range strings.Split(text, "\n") {
		trimmed := strip(line)
		for spanIndex < len(protectedSpans) && offset >= protectedSpans[spanIndex].end {
			spanIndex++
		}
		protectedLine := spanIndex < len(protectedSpans) && offset >= protectedSpans[spanIndex].start
		offset += len(line) + 1
		newline := ""
		if offset <= len(text) {
			newline = "\n"
		}
		if match := headingPattern.FindStringSubmatch(trimmed); !protectedLine && match != nil {
			flush()
			level := len(match[1])
			stack[level-1] = match[2]
			for i := level; i < 6; i++ {
				stack[i] = ""
			}
			path = nil
			for _, s := range stack {
				if s != "" {
					path = append(path, s)
				}
			}
			body.WriteString(trimmed + newline)
			continue
		}
		body.WriteString(line + newline)
	}
	flush()
	var pieces []piece
	for _, s := range sections {
		raw := trimOutsideMarkdownBlocks(s.text, markdownBlocks(s.text))
		if raw == "" {
			continue
		}
		spans := markdownBlocks(raw)
		blocks := []markdownChunk{{raw, len(spans) > 0 || len(markdownInlineCode(raw)) > 0}}
		if chars(raw) > target {
			blocks = nil
			var units []markdownUnit
			start := 0
			for _, span := range spans {
				units = appendMarkdownParagraphs(units, raw[start:span.start], start)
				units = append(units, markdownUnit{raw[span.start:span.end], true, span.start, span.end})
				start = span.end
			}
			units = appendMarkdownParagraphs(units, raw[start:], start)
			current := ""
			protected := false
			for index, unit := range units {
				p := unit.text
				if chars(p) > target {
					if current != "" {
						blocks = append(blocks, markdownChunk{current, protected})
						current = ""
						protected = false
					}
					if unit.protected {
						blocks = append(blocks, markdownChunk{p, true})
					} else {
						blocks = append(blocks, hardSplit(p, target)...)
					}
					continue
				}
				// 原子结构与相邻文本拼接时保留原有分隔，不凭空增加空行。
				separator := "\n\n"
				if index > 0 && (unit.protected || units[index-1].protected) {
					separator = raw[units[index-1].end:unit.start]
				}
				if chars(current)+chars(p)+chars(separator) > target && current != "" {
					blocks = append(blocks, markdownChunk{current, protected})
					current = ""
					protected = false
				}
				if current != "" {
					current += separator
				}
				current += p
				protected = protected || unit.protected || len(markdownInlineCode(p)) > 0
			}
			if current != "" {
				blocks = append(blocks, markdownChunk{current, protected})
			}
			original := append([]markdownChunk(nil), blocks...)
			for i := 1; overlap > 0 && i < len(blocks); i++ {
				if original[i-1].protected || original[i].protected {
					continue
				}
				u := utf16.Encode([]rune(original[i-1].text))
				start := max(0, len(u)-overlap)
				if start < len(u) && u[start] >= 0xDC00 && u[start] <= 0xDFFF {
					start++
				}
				tail := string(utf16.Decode(u[start:]))
				blocks[i].text = "…" + tail + "\n" + blocks[i].text
			}
		}
		for _, b := range blocks {
			if strip(b.text) != "" {
				pieces = append(pieces, piece{s.path, b.text})
			}
		}
	}
	var merged []piece
	for _, p := range pieces {
		if len(merged) > 0 && chars(p.text) < minimum {
			prev := &merged[len(merged)-1]
			if chars(prev.text)+chars(p.text)+2 <= target*3/2 {
				prev.text += "\n\n" + p.text
				continue
			}
		}
		merged = append(merged, p)
	}
	for _, p := range merged {
		prefix := ""
		if len(p.path) > 0 {
			path := strings.Join(p.path, " > ")
			if chars(path) > 120 {
				path = truncate(path, 120) + "…"
			}
			prefix = "【章节：" + path + "】\n"
		}
		result = append(result, prefix+p.text)
	}
	return result
}
func hardSplit(text string, target int) []markdownChunk {
	var out []markdownChunk
	for chars(text) > target {
		u := utf16.Encode([]rune(text))
		cut := target
		found := false
		floor := target * 7 / 10
		for i := target - 1; i >= floor; i-- {
			if strings.ContainsRune("。！？；\n.", rune(u[i])) {
				cut = i + 1
				found = true
				break
			}
		}
		if !found {
			for i := target - 1; i >= floor; i-- {
				if strip(string(rune(u[i]))) == "" {
					cut = i + 1
					break
				}
			}
		}
		for _, span := range markdownInlineCode(text) {
			start, end := chars(text[:span.start]), chars(text[:span.end])
			if start < cut && cut < end {
				if start > 0 {
					cut = start
				} else {
					cut = end
				}
				break
			}
		}
		// 不生成半个 Unicode 码点；SQL/JSON 都需要有效 UTF-8。
		if cut < len(u) && cut > 0 && u[cut-1] >= 0xD800 && u[cut-1] <= 0xDBFF {
			cut++
		}
		value := strip(string(utf16.Decode(u[:cut])))
		out = append(out, markdownChunk{value, len(markdownInlineCode(value)) > 0})
		text = strip(string(utf16.Decode(u[cut:])))
	}
	if text != "" {
		out = append(out, markdownChunk{text, len(markdownInlineCode(text)) > 0})
	}
	return out
}

func appendMarkdownParagraphs(units []markdownUnit, text string, offset int) []markdownUnit {
	inline := markdownInlineCode(text)
	start, span := 0, 0
	for _, separator := range paragraphPattern.FindAllStringIndex(text, -1) {
		for span < len(inline) && inline[span].end <= separator[0] {
			span++
		}
		if span < len(inline) && inline[span].start < separator[1] {
			continue
		}
		if value := strip(text[start:separator[0]]); value != "" {
			position := offset + start + strings.Index(text[start:separator[0]], value)
			units = append(units, markdownUnit{value, false, position, position + len(value)})
		}
		start = separator[1]
	}
	if value := strip(text[start:]); value != "" {
		position := offset + start + strings.Index(text[start:], value)
		units = append(units, markdownUnit{value, false, position, position + len(value)})
	}
	return units
}
