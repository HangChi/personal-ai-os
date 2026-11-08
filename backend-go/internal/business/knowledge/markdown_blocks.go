package knowledge

import (
	"regexp"
	"strings"
)

// 原文区间使用字节偏移，长度预算仍使用兼容 Java 的 UTF-16 计数。
type markdownSpan struct{ start, end int }
type markdownLine struct {
	text       string
	start, end int
}
type markdownFence struct {
	marker byte
	length int
}

var markdownFencePattern = regexp.MustCompile("^(`{3,}|~{3,})(.*)$")
var markdownListPattern = regexp.MustCompile(`^([ \t]*)(?:[-+*]|[0-9]{1,9}[.)])[ \t]+\S.*$`)
var markdownHeadingPattern = regexp.MustCompile(`^#{1,6}\s+.*$`)
var markdownTableSeparator = regexp.MustCompile(`^\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)*\|?$`)

// markdownBlocks 只识别原子结构，不重写代码缩进、表格行和公式内容。
func markdownBlocks(text string) []markdownSpan {
	var lines []markdownLine
	offset := 0
	for _, line := range strings.Split(text, "\n") {
		lines = append(lines, markdownLine{line, offset, offset + len(line)})
		offset += len(line) + 1
	}
	var spans []markdownSpan
	for i := 0; i < len(lines); {
		line := lines[i].text
		trimmed := strip(line)
		end := i
		fence, fenced := openingMarkdownFence(line)
		switch {
		case fenced:
			end = len(lines)
			for j := i + 1; j < len(lines); j++ {
				if closesMarkdownFence(lines[j].text, fence) {
					end = j + 1
					break
				}
			}
		case trimmed != "" && markdownIndentation(line) >= 4 && (i == 0 || strip(lines[i-1].text) == "" || markdownHeadingPattern.MatchString(strip(lines[i-1].text))):
			// 缩进代码优先于其内部的引用/列表/公式符号。
			end = i + 1
			for end < len(lines) {
				next := skipMarkdownBlank(lines, end)
				if next >= len(lines) || markdownIndentation(lines[next].text) < 4 {
					break
				}
				end = next + 1
			}
		case strings.HasPrefix(trimmed, "$$") || strings.HasPrefix(trimmed, `\[`):
			close := "$$"
			if strings.HasPrefix(trimmed, `\[`) {
				close = `\]`
			}
			end = i + 1
			if !strings.Contains(trimmed[2:], close) {
				for end < len(lines) && !strings.Contains(strip(lines[end].text), close) {
					end++
				}
				if end < len(lines) {
					end++
				}
			}
		case i+1 < len(lines) && strings.Contains(trimmed, "|") && markdownTableSeparator.MatchString(strip(lines[i+1].text)):
			end = i + 2
			for end < len(lines) && strip(lines[end].text) != "" && strings.Contains(lines[end].text, "|") {
				end++
			}
		case strings.Contains(trimmed, "\t") && i+1 < len(lines) && strings.Contains(strip(lines[i+1].text), "\t"):
			end = i + 1
			for end < len(lines) && strings.Contains(strip(lines[end].text), "\t") {
				end++
			}
		case strings.HasPrefix(trimmed, ">"):
			end = i + 1
			for end < len(lines) {
				next := skipMarkdownBlank(lines, end)
				if next >= len(lines) {
					break
				}
				following := lines[next].text
				if !strings.HasPrefix(strip(following), ">") && (next != end || startsMarkdownBlock(following) || markdownListPattern.MatchString(following)) {
					break
				}
				end = next + 1
			}
		case markdownListPattern.MatchString(line):
			base := markdownIndentation(line)
			end = i + 1
			for end < len(lines) {
				next := skipMarkdownBlank(lines, end)
				if next >= len(lines) {
					break
				}
				following := lines[next].text
				continuation := markdownIndentation(following) > base || markdownListPattern.MatchString(following)
				if !continuation && next == end && !startsMarkdownBlock(following) {
					continuation = true
				}
				if !continuation {
					break
				}
				end = next + 1
			}
		}
		if end > i {
			spans = append(spans, markdownSpan{lines[i].start, lines[end-1].end})
			i = end
		} else {
			i++
		}
	}
	return spans
}

func markdownInlineCode(text string) []markdownSpan {
	var runs []markdownSpan
	for i := 0; i < len(text); {
		if text[i] != '`' {
			i++
			continue
		}
		start := i
		for i < len(text) && text[i] == '`' {
			i++
		}
		runs = append(runs, markdownSpan{start, i})
	}
	// 预先定位同长度的下一个分隔符，避免未闭合反引号导致反复扫描。
	next := make([]int, len(runs))
	byLength := make(map[int]int)
	for i := len(runs) - 1; i >= 0; i-- {
		length := runs[i].end - runs[i].start
		next[i] = -1
		if closing, ok := byLength[length]; ok {
			next[i] = closing
		}
		byLength[length] = i
	}
	var spans []markdownSpan
	for i := 0; i < len(runs); {
		if markdownEscaped(text, runs[i].start) || next[i] < 0 {
			i++
			continue
		}
		closing := next[i]
		spans = append(spans, markdownSpan{runs[i].start, runs[closing].end})
		i = closing + 1
	}
	return spans
}

func markdownHeadingProtection(text string) []markdownSpan {
	var result []markdownSpan
	offset := 0
	for _, block := range markdownBlocks(text) {
		for _, inline := range markdownInlineCode(text[offset:block.start]) {
			result = append(result, markdownSpan{offset + inline.start, offset + inline.end})
		}
		result = append(result, block)
		offset = block.end
	}
	for _, inline := range markdownInlineCode(text[offset:]) {
		result = append(result, markdownSpan{offset + inline.start, offset + inline.end})
	}
	return result
}

func trimOutsideMarkdownBlocks(text string, spans []markdownSpan) string {
	value := strip(text)
	if value == "" && len(spans) == 0 {
		return ""
	}
	start := strings.Index(text, value)
	end := start + len(value)
	if len(spans) > 0 {
		start = min(start, spans[0].start)
		end = max(end, spans[len(spans)-1].end)
	}
	return text[start:end]
}

func openingMarkdownFence(line string) (markdownFence, bool) {
	if markdownIndentation(line) > 3 {
		return markdownFence{}, false
	}
	match := markdownFencePattern.FindStringSubmatch(strip(line))
	if match == nil || (match[1][0] == '`' && strings.Contains(match[2], "`")) {
		return markdownFence{}, false
	}
	return markdownFence{match[1][0], len(match[1])}, true
}

func closesMarkdownFence(line string, fence markdownFence) bool {
	if markdownIndentation(line) > 3 {
		return false
	}
	value := strip(line)
	count := 0
	for count < len(value) && value[count] == fence.marker {
		count++
	}
	return count >= fence.length && strip(value[count:]) == ""
}

func startsMarkdownBlock(line string) bool {
	s := strip(line)
	_, fenced := openingMarkdownFence(line)
	return strings.HasPrefix(s, "#") || strings.HasPrefix(s, ">") || strings.HasPrefix(s, "$$") || strings.HasPrefix(s, `\[`) || fenced || strings.ContainsAny(s, "|\t")
}

func skipMarkdownBlank(lines []markdownLine, index int) int {
	for index < len(lines) && strip(lines[index].text) == "" {
		index++
	}
	return index
}

func markdownIndentation(line string) int {
	width := 0
	for i := 0; i < len(line); i++ {
		if line[i] == ' ' {
			width++
		} else if line[i] == '\t' {
			width += 4 - width%4
		} else {
			break
		}
	}
	return width
}

func markdownEscaped(text string, index int) bool {
	slashes := 0
	for index > 0 && text[index-1] == '\\' {
		slashes++
		index--
	}
	return slashes%2 != 0
}
