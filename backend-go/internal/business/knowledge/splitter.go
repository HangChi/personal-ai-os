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
	inFence := false
	flush := func() {
		if strip(body.String()) != "" || len(path) > 0 {
			sections = append(sections, piece{append([]string(nil), path...), body.String()})
		}
		body.Reset()
	}
	for _, line := range strings.Split(text, "\n") {
		trimmed := strip(line)
		if strings.HasPrefix(trimmed, "~~~") || strings.HasPrefix(trimmed, string([]byte{96, 96, 96})) {
			inFence = !inFence
			body.WriteString(line + "\n")
			continue
		}
		if match := headingPattern.FindStringSubmatch(trimmed); !inFence && match != nil {
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
			body.WriteString(trimmed + "\n")
			continue
		}
		body.WriteString(line + "\n")
	}
	flush()
	var pieces []piece
	for _, s := range sections {
		raw := strip(s.text)
		if raw == "" {
			continue
		}
		blocks := []string{raw}
		if chars(raw) > target {
			blocks = nil
			current := ""
			for _, paragraph := range paragraphPattern.Split(raw, -1) {
				p := strip(paragraph)
				if p == "" {
					continue
				}
				if chars(p) > target {
					if current != "" {
						blocks = append(blocks, current)
						current = ""
					}
					blocks = append(blocks, hardSplit(p, target)...)
					continue
				}
				if chars(current)+chars(p)+2 > target && current != "" {
					blocks = append(blocks, current)
					current = ""
				}
				if current != "" {
					current += "\n\n"
				}
				current += p
			}
			if current != "" {
				blocks = append(blocks, current)
			}
			original := append([]string(nil), blocks...)
			for i := 1; overlap > 0 && i < len(blocks); i++ {
				u := utf16.Encode([]rune(original[i-1]))
				tail := string(utf16.Decode(u[max(0, len(u)-overlap):]))
				blocks[i] = "…" + tail + "\n" + blocks[i]
			}
		}
		for _, b := range blocks {
			if strip(b) != "" {
				pieces = append(pieces, piece{s.path, strip(b)})
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
func hardSplit(text string, target int) []string {
	var out []string
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
		// 不生成半个 Unicode 码点；SQL/JSON 都需要有效 UTF-8。
		if cut < len(u) && cut > 0 && u[cut-1] >= 0xD800 && u[cut-1] <= 0xDBFF {
			cut++
		}
		out = append(out, strip(string(utf16.Decode(u[:cut]))))
		text = strip(string(utf16.Decode(u[cut:])))
	}
	if text != "" {
		out = append(out, text)
	}
	return out
}
