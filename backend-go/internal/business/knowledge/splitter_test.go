package knowledge

import (
	"context"
	"strings"
	"testing"
)

func TestMarkdownStructureAndNoLoss(t *testing.T) {
	text := "# 数据库\n" + strings.Repeat("事务。", 100) + "\n## 隔离\n" + strings.Repeat("隔离。", 100) + "\n# 索引\n" + strings.Repeat("索引。", 100)
	blocks := SplitMarkdown(text, 150, 50, 0)
	joined := strings.Join(blocks, "")
	for _, s := range []string{"【章节：数据库】", "【章节：数据库 > 隔离】", "【章节：索引】"} {
		if !strings.Contains(joined, s) {
			t.Fatal(joined)
		}
	}
	for _, token := range []string{"事务。", "隔离。", "索引。"} {
		if strings.Count(joined, token) != 100 {
			t.Fatal("丢失正文", token)
		}
	}
	for _, b := range blocks {
		if chars(b) > 250 {
			t.Fatal(chars(b))
		}
	}
}
func TestMarkdownFenceOverlapMinimumAndLongPrefix(t *testing.T) {
	fence := string([]byte{96, 96, 96})
	text := "# Real\n" + fence + "python\n# comment\n## fake\n" + fence + "\n\nnormal text"
	b := SplitMarkdown(text, 1000, 1, 0)
	if len(b) != 1 || !strings.HasPrefix(b[0], "【章节：Real】") || !strings.Contains(b[0], "# comment") {
		t.Fatal(b)
	}
	b = SplitMarkdown("1234567890\n\nabcdefghij\n\nxyz", 12, 1, 3)
	if len(b) != 3 || b[1] != "…890\nabcdefghij" || b[2] != "…hij\nxyz" {
		t.Fatal(b)
	}
	b = SplitMarkdown("# A\nabcdef\n# B\nx", 20, 10, 0)
	if len(b) != 1 || strings.Count(b[0], "【章节：") != 1 {
		t.Fatal(b)
	}
	b = SplitMarkdown("# "+strings.Repeat("长", 200)+"\nx", 1000, 1, 0)
	if !strings.Contains(b[0], strings.Repeat("长", 120)+"…】") {
		t.Fatal(b)
	}
	if len(SplitMarkdown(" \n", 10, 1, 0)) != 0 {
		t.Fatal("blank")
	}
}

func TestMarkdownOversizedProtectedStructures(t *testing.T) {
	structures := []string{
		"```python\n    # 注释不是标题\n\n" + strings.Repeat("    print('完整代码')  \n", 180) + "```",
		"    # 缩进代码\n\n" + strings.Repeat("    print('保留缩进')  \n", 180),
		"| 名称 | 值 |\n| --- | --- |\n" + strings.Repeat("| key | 完整表格内容 |\n", 180),
		"名称\t值\n" + strings.Repeat("key\t完整表格内容\n", 180),
		"- 父项\n  - 子项\n\n" + strings.Repeat("- 完整列表内容\n", 180),
		"> 引用开始\n>\n" + strings.Repeat("> 完整引用内容\n", 180),
		"$$\n" + strings.Repeat("x = a + b + c + d\n", 180) + "$$",
		"`" + strings.Repeat("inline_call_", 180) + "`",
	}
	for _, structure := range structures {
		exact := strings.TrimRight(structure, " \t\r\n")
		blocks := SplitMarkdown("# 文档\n\n前言。\n\n"+exact+"\n\n后记。", 1200, 350, 120)
		whole := 0
		for _, block := range blocks {
			if strings.Contains(block, exact) {
				whole++
			}
		}
		joined := strings.Join(blocks, "\n")
		if whole != 1 || strings.Count(joined, exact) != 1 || !strings.Contains(joined, "前言。") || !strings.Contains(joined, "后记。") {
			t.Fatalf("结构被截断、复制或正文丢失：%q", blocks)
		}
	}
}

func TestMarkdownCodeWhitespaceAndPlainTextBudget(t *testing.T) {
	code := "    first = 1  \n\n    second = 2\t "
	blocks := SplitMarkdown(code, 12, 1, 8)
	if len(blocks) != 1 || blocks[0] != code {
		t.Fatalf("代码空白变化：%q", blocks)
	}
	text := strings.Repeat("普通正文。", 1000)
	blocks = SplitMarkdown(text, 1200, 1, 0)
	if len(blocks) < 2 || strings.Join(blocks, "") != text {
		t.Fatal("普通文本切分回归")
	}
	for _, block := range blocks {
		if chars(block) > 1200 {
			t.Fatal("普通块超限")
		}
	}
}

func TestMarkdownUTF8UploadKeepsProtectedCode(t *testing.T) {
	code := "   ```python\r\n    # 原始注释\r\n\r\n    value = 1  \r\n   ```"
	reader := Reader{Properties: testProperties(t)}
	upload, err := reader.Prepare(context.Background(), "code.md", "", []byte("\uFEFF"+code))
	if err != nil {
		t.Fatal(err)
	}
	chunks := SplitMarkdown(upload.Text, 12, 1, 8)
	if len(chunks) != 1 || chunks[0] != code || upload.Filename != "code.md" {
		t.Fatalf("读取或切分改变代码：%+v %q", upload, chunks)
	}
}
