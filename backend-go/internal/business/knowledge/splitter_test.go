package knowledge

import (
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
