// Package knowledge 实现知识摄入、检索与来源治理。
package knowledge

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf16"
	"unicode/utf8"

	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

func strip(s string) string {
	return strings.TrimFunc(s, func(r rune) bool {
		return unicode.IsSpace(r) && r != '\u0085' && r != '\u00a0' && r != '\u2007' && r != '\u202f' || r >= '\x1c' && r <= '\x1f'
	})
}
func chars(s string) int { return len(utf16.Encode([]rune(s))) }
func truncate(s string, n int) string {
	u := utf16.Encode([]rune(s))
	if len(u) > n {
		u = u[:max(n, 0)]
	}
	return string(utf16.Decode(u))
}
func unsupported(message string) error { return api.NewBusinessError(api.UnsupportedDocument, message) }

func SanitizeFilename(s string) string {
	if strip(s) == "" {
		return "untitled.txt"
	}
	s = strings.ReplaceAll(s, "\\", "/")
	s = s[strings.LastIndex(s, "/")+1:]
	if strip(s) == "" {
		return "untitled.txt"
	}
	if chars(s) > 300 {
		ext := ""
		if i := strings.LastIndex(s, "."); i > 0 && chars(s[i:]) <= 10 {
			ext = s[i:]
		}
		s = truncate(s, 300-chars(ext)) + ext
	}
	return s
}
func extension(s string) string {
	if i := strings.LastIndex(s, "."); i >= 0 {
		return strings.ToLower(s[i+1:])
	}
	return ""
}
func titleOf(s string) string {
	if i := strings.LastIndex(s, "."); i > 0 && strip(s[:i]) != "" {
		s = s[:i]
	}
	return truncate(s, 300)
}
func contentType(ext string) string {
	switch ext {
	case "md", "markdown":
		return "text/markdown"
	case "pdf":
		return "application/pdf"
	case "docx":
		return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
	case "png":
		return "image/png"
	case "jpg", "jpeg":
		return "image/jpeg"
	case "webp":
		return "image/webp"
	case "gif":
		return "image/gif"
	}
	return "text/plain"
}

type VisionExtractor func(context.Context, []byte, string) (string, error)
type Reader struct {
	Properties config.KnowledgeProperties
	Vision     VisionExtractor
}
type Upload struct {
	Title, Filename, ContentType, Text string
	SizeBytes                          int64
	CharCount                          int
}

func (r Reader) Validate(filename string, data []byte) (string, string, error) {
	filename = SanitizeFilename(filename)
	ext := extension(filename)
	if !r.Properties.IsExtensionAllowed(ext) {
		display := ext
		if display == "" {
			display = "(无扩展名)"
		}
		return "", "", unsupported(fmt.Sprintf("不支持的文件类型「%s」，当前仅支持：%s", display, strings.Join(r.Properties.AllowedExtensions(), " / ")))
	}
	if len(data) == 0 {
		return "", "", unsupported("文件内容为空")
	}
	if int64(len(data)) > r.Properties.MaxFileSizeBytes {
		return "", "", unsupported(fmt.Sprintf("文件过大（%d 字节），上限为 %d 字节", len(data), r.Properties.MaxFileSizeBytes))
	}
	return filename, ext, nil
}
func (r Reader) Prepare(ctx context.Context, filename, title string, data []byte) (Upload, error) {
	filename, ext, err := r.Validate(filename, data)
	if err != nil {
		return Upload{}, err
	}
	if err = ctx.Err(); err != nil {
		return Upload{}, err
	}
	mime := contentType(ext)
	text := ""
	switch ext {
	case "pdf":
		text, err = readPDF(ctx, data)
	case "docx":
		text, err = readDOCX(data)
	case "png", "jpg", "jpeg", "webp", "gif":
		if r.Vision == nil {
			return Upload{}, unsupported("当前未配置 AI 模型，无法解析图片（请先在 .env 中配置 ollama 或 openai）")
		}
		text, err = r.Vision(ctx, data, mime)
		if strip(text) == "无文本" {
			text = ""
		}
	default:
		if !utf8.Valid(data) {
			return Upload{}, unsupported("文件不是有效的 UTF-8 编码，请转换编码后重新上传（当前仅支持 UTF-8）")
		}
		text = strings.TrimPrefix(string(data), "\uFEFF")
	}
	if err != nil {
		return Upload{}, err
	}
	if ext == "pdf" || ext == "docx" || strings.HasPrefix(mime, "image/") {
		text = strip(strings.ReplaceAll(text, "\uFEFF", ""))
		if text == "" {
			return Upload{}, unsupported("未提取到文本（如果是扫描件或纯图片，需要 OCR，当前未支持）")
		}
	}
	if strip(text) == "" {
		text = ""
	}
	n := chars(text)
	if n < r.Properties.MinChunkSizeChars {
		return Upload{}, unsupported(fmt.Sprintf("解析后内容仅 %d 字符，少于 %d，无法形成有效分块", n, r.Properties.MinChunkSizeChars))
	}
	title = strip(title)
	if title == "" {
		title = titleOf(filename)
	}
	return Upload{truncate(title, 300), filename, mime, text, int64(len(data)), n}, nil
}

type limitedOutput struct {
	bytes.Buffer
	max int
}

func (b *limitedOutput) Write(p []byte) (int, error) {
	if len(p) > b.max-b.Len() {
		return 0, errors.New("解析结果超出安全上限")
	}
	return b.Buffer.Write(p)
}
func pdfCommand(ctx context.Context, binary string, data []byte, args ...string) (string, error) {
	command := exec.CommandContext(ctx, binary, args...)
	command.Stdin = bytes.NewReader(data)
	command.WaitDelay = time.Second
	out := &limitedOutput{max: 64 << 20}
	diagnostic := &limitedOutput{max: 8192}
	command.Stdout = out
	command.Stderr = diagnostic
	if err := command.Run(); err != nil {
		if ctx.Err() != nil {
			return "", unsupported("PDF 解析超时或已取消")
		}
		if strings.Contains(strings.ToLower(diagnostic.String()), "password") {
			return "", unsupported("PDF 已加密，无法提取文本（请先去除密码后重新上传）")
		}
		return "", unsupported("PDF 解析失败：文件可能已损坏，或不是有效的 PDF")
	}
	return out.String(), nil
}
func readPDF(ctx context.Context, data []byte) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	info, err := pdfCommand(ctx, "pdfinfo", data, "-")
	if err != nil {
		return "", err
	}
	pages := 0
	for _, line := range strings.Split(info, "\n") {
		if strings.HasPrefix(line, "Pages:") {
			pages, _ = strconv.Atoi(strings.TrimSpace(strings.TrimPrefix(line, "Pages:")))
		}
	}
	if pages > 500 {
		return "", unsupported("PDF 页数超过上限（500 页），请拆分后分批上传")
	}
	if pages < 1 {
		return "", unsupported("PDF 解析失败：文件可能已损坏，或不是有效的 PDF")
	}
	return pdfCommand(ctx, "pdftotext", data, "-enc", "UTF-8", "-nopgbrk", "-", "-")
}

type xmlNode struct {
	Name     xml.Name
	Text     string
	Children []*xmlNode
}

func parseNode(d *xml.Decoder, start xml.StartElement, depth int) (*xmlNode, error) {
	if depth > 128 {
		return nil, errors.New("XML嵌套超限")
	}
	n := &xmlNode{Name: start.Name}
	for {
		tok, err := d.Token()
		if err != nil {
			return nil, err
		}
		switch t := tok.(type) {
		case xml.StartElement:
			c, e := parseNode(d, t, depth+1)
			if e != nil {
				return nil, e
			}
			n.Children = append(n.Children, c)
		case xml.CharData:
			n.Text += string(t)
		case xml.EndElement:
			return n, nil
		}
	}
}
func descendants(n *xmlNode, name string) []*xmlNode {
	var result []*xmlNode
	for _, c := range n.Children {
		if c.Name.Local == name {
			result = append(result, c)
		} else {
			result = append(result, descendants(c, name)...)
		}
	}
	return result
}
func paragraphText(n *xmlNode) string {
	var b strings.Builder
	var walk func(*xmlNode)
	walk = func(c *xmlNode) {
		switch c.Name.Local {
		case "t":
			b.WriteString(c.Text)
		case "tab":
			b.WriteByte('\t')
		case "br", "cr":
			b.WriteByte('\n')
		default:
			for _, child := range c.Children {
				walk(child)
			}
		}
	}
	walk(n)
	return b.String()
}
func readDOCX(data []byte) (string, error) {
	bad := unsupported("Word 解析失败：文件可能已损坏，或不是有效的 .docx（旧版 .doc 暂不支持）")
	z, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return "", bad
	}
	if len(z.File) > 10000 {
		return "", bad
	}
	var document *zip.File
	types := false
	var total uint64
	for _, f := range z.File {
		total += f.UncompressedSize64
		if total > 64<<20 || f.UncompressedSize64 > 64<<20 || f.UncompressedSize64 > 102400 && f.CompressedSize64*100 < f.UncompressedSize64 {
			return "", bad
		}
		if f.Name == "[Content_Types].xml" {
			types = true
		}
		if f.Name == "word/document.xml" {
			if document != nil {
				return "", bad
			}
			document = f
		}
	}
	if document == nil || !types {
		return "", bad
	}
	in, err := document.Open()
	if err != nil {
		return "", bad
	}
	defer in.Close()
	d := xml.NewDecoder(io.LimitReader(in, 64<<20))
	var root *xmlNode
	for {
		t, e := d.Token()
		if e != nil {
			return "", bad
		}
		if start, ok := t.(xml.StartElement); ok {
			root, err = parseNode(d, start, 0)
			break
		}
	}
	if err != nil || root.Name.Local != "document" {
		return "", bad
	}
	bodies := descendants(root, "body")
	if len(bodies) != 1 {
		return "", bad
	}
	var lines []string
	appendLine := func(s string) {
		s = strip(s)
		if s != "" {
			lines = append(lines, s)
		}
	}
	for _, element := range bodies[0].Children {
		switch element.Name.Local {
		case "p":
			appendLine(paragraphText(element))
		case "tbl":
			for _, row := range descendants(element, "tr") {
				var cells []string
				for _, cell := range row.Children {
					if cell.Name.Local != "tc" {
						continue
					}
					var ps []string
					for _, p := range cell.Children {
						if p.Name.Local == "p" {
							s := strip(paragraphText(p))
							if s != "" {
								ps = append(ps, s)
							}
						}
					}
					cells = append(cells, strings.Join(ps, " "))
				}
				appendLine(strings.Join(cells, "\t"))
			}
		}
	}
	return strings.Join(lines, "\n"), nil
}
