package knowledge

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
	"strings"
	"testing"
)

func testProperties(t *testing.T) config.KnowledgeProperties {
	t.Helper()
	p, e := config.BindKnowledgeProperties(map[string]string{"app.knowledge.allowed-extensions[0]": "txt", "app.knowledge.allowed-extensions[1]": "md", "app.knowledge.allowed-extensions[2]": "pdf", "app.knowledge.allowed-extensions[3]": "docx", "app.knowledge.allowed-extensions[4]": "png", "app.knowledge.allowed-extensions[5]": "jpg", "app.knowledge.min-chunk-size-chars": "10"})
	if e != nil {
		t.Fatal(e)
	}
	return p
}
func expectBusiness(t *testing.T, e error, code api.ErrorCode, part string) {
	t.Helper()
	var b *api.BusinessError
	if !errors.As(e, &b) || b.Code() != code || !strings.Contains(b.Error(), part) {
		t.Fatalf("错误 %v; 期待 %v %s", e, code, part)
	}
}
func TestFilenameAndValidation(t *testing.T) {
	for _, tt := range [][2]string{{"../a.TXT", "a.TXT"}, {"C:\\fakepath\\a.txt", "a.txt"}, {"a/", "untitled.txt"}, {"", "untitled.txt"}, {strings.Repeat("中", 350) + ".pdf", strings.Repeat("中", 296) + ".pdf"}} {
		if got := SanitizeFilename(tt[0]); got != tt[1] {
			t.Fatalf("%q: %q", tt[0], got)
		}
	}
	r := Reader{Properties: testProperties(t)}
	ctx := context.Background()
	for _, tt := range []struct {
		name string
		data []byte
		part string
	}{{"x.exe", []byte("abc"), "文件类型"}, {"x.txt", nil, "为空"}, {"x.txt", []byte{0xff}, "UTF-8"}, {"x.txt", []byte("tiny"), "少于"}, {"x.txt", []byte("  \n"), "少于"}} {
		_, e := r.Prepare(ctx, tt.name, "", tt.data)
		expectBusiness(t, e, api.UnsupportedDocument, tt.part)
	}
	r.Properties.MaxFileSizeBytes = 12
	_, e := r.Prepare(ctx, "x.txt", "", []byte(strings.Repeat("a", 13)))
	expectBusiness(t, e, api.UnsupportedDocument, "文件过大")
	u, e := r.Prepare(ctx, "x.txt", " title ", []byte("\uFEFF1234567890"))
	if e != nil {
		r.Properties.MaxFileSizeBytes = 20
		u, e = r.Prepare(ctx, "x.txt", " title ", []byte("\uFEFF1234567890"))
	}
	if e != nil || u.Title != "title" || u.CharCount != 10 || u.Text != "1234567890" {
		t.Fatalf("%+v %v", u, e)
	}
}
func docxFixture(t *testing.T, body string) []byte {
	t.Helper()
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	for name, content := range map[string]string{"[Content_Types].xml": "<Types/>", "word/document.xml": "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>" + body + "</w:body></w:document>"} {
		f, e := z.Create(name)
		if e != nil {
			t.Fatal(e)
		}
		if _, e = f.Write([]byte(content)); e != nil {
			t.Fatal(e)
		}
	}
	if e := z.Close(); e != nil {
		t.Fatal(e)
	}
	return b.Bytes()
}
func TestDOCXParagraphsTablesAndSafety(t *testing.T) {
	body := "<w:p><w:r><w:t>第一段数据库事务</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>隔离级别</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>可重复读</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
	r := Reader{Properties: testProperties(t)}
	u, e := r.Prepare(context.Background(), "x.docx", "", docxFixture(t, body))
	if e != nil || u.Text != "第一段数据库事务\n隔离级别\t可重复读" {
		t.Fatalf("%+v %v", u, e)
	}
	_, e = r.Prepare(context.Background(), "x.docx", "", docxFixture(t, ""))
	expectBusiness(t, e, api.UnsupportedDocument, "未提取到文本")
	for _, b := range [][]byte{[]byte("not a docx"), docxFixture(t, "<x>"), docxFixture(t, strings.Repeat("<x>", 150)+strings.Repeat("</x>", 150)), docxFixture(t, strings.Repeat("x", 200000))} {
		_, e = r.Prepare(context.Background(), "x.docx", "", b)
		expectBusiness(t, e, api.UnsupportedDocument, "Word")
	}
}
func TestVisionNormalizationMIMEAndErrors(t *testing.T) {
	r := Reader{Properties: testProperties(t)}
	ctx := context.Background()
	_, e := r.Prepare(ctx, "x.png", "", []byte{1})
	expectBusiness(t, e, api.UnsupportedDocument, "未配置")
	for _, s := range []string{"", "  \n", "无文本"} {
		r.Vision = func(context.Context, []byte, string) (string, error) { return s, nil }
		_, e = r.Prepare(ctx, "x.png", "", []byte{1})
		expectBusiness(t, e, api.UnsupportedDocument, "未提取到文本")
	}
	r.Vision = func(_ context.Context, b []byte, mime string) (string, error) {
		if mime != "image/jpeg" || !bytes.Equal(b, []byte{1}) {
			t.Fatal(mime, b)
		}
		return "\uFEFF  板书内容：光合作用需要光照 ", nil
	}
	u, e := r.Prepare(ctx, "x.JPG", "", []byte{1})
	if e != nil || u.Text != "板书内容：光合作用需要光照" {
		t.Fatal(u, e)
	}
	upstream := api.NewBusinessError(api.AIUpstreamError, "upstream")
	r.Vision = func(context.Context, []byte, string) (string, error) { return "", upstream }
	_, e = r.Prepare(ctx, "x.png", "", []byte{1})
	if e != upstream {
		t.Fatal(e)
	}
}
func pdfFixture(text string, pages int) []byte {
	texts := make([]string, pages)
	for i := range texts {
		texts[i] = text
	}
	return pdfFixturePages(texts...)
}
func pdfFixturePages(texts ...string) []byte {
	var b bytes.Buffer
	fmt.Fprint(&b, "%PDF-1.4\n")
	objects := []string{"<< /Type /Catalog /Pages 2 0 R >>", fmt.Sprintf("<< /Type /Pages /Count %d /Kids [", len(texts))}
	for i := range texts {
		objects[1] += fmt.Sprintf("%d 0 R ", 4+2*i)
	}
	objects[1] += "] >>"
	objects = append(objects, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
	for i, value := range texts {
		stream := "q 80 0 0 80 10 10 cm BI /W 1 /H 1 /CS /DeviceGray /BPC 8 /F /ASCIIHexDecode ID 00> EI Q"
		if value != "" {
			stream = "BT /F1 12 Tf 10 70 Td (" + value + ") Tj ET"
		}
		objects = append(objects,
			fmt.Sprintf("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 120 120] /Contents %d 0 R /Resources << /Font << /F1 3 0 R >> >> >>", 5+2*i),
			fmt.Sprintf("<< /Length %d >>\nstream\n%s\nendstream", len(stream), stream))
	}
	offsets := []int{0}
	for i, obj := range objects {
		offsets = append(offsets, b.Len())
		fmt.Fprintf(&b, "%d 0 obj\n%s\nendobj\n", i+1, obj)
	}
	xref := b.Len()
	fmt.Fprintf(&b, "xref\n0 %d\n0000000000 65535 f \n", len(offsets))
	for _, o := range offsets[1:] {
		fmt.Fprintf(&b, "%010d 00000 n \n", o)
	}
	fmt.Fprintf(&b, "trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", len(offsets), xref)
	return b.Bytes()
}
func TestPDFTextEmptyBrokenAndPageLimit(t *testing.T) {
	r := Reader{Properties: testProperties(t)}
	u, e := r.Prepare(context.Background(), "x.pdf", "", pdfFixture("PostgreSQL transaction isolation levels", 1))
	if e != nil || !strings.Contains(u.Text, "PostgreSQL") {
		t.Fatal(u, e)
	}
	for _, tt := range []struct {
		b    []byte
		part string
	}{{pdfFixture("", 1), "视觉模型"}, {[]byte("broken"), "PDF 解析失败"}, {pdfFixture("hello", 501), "500 页"}} {
		_, e = r.Prepare(context.Background(), "x.pdf", "", tt.b)
		expectBusiness(t, e, api.UnsupportedDocument, tt.part)
	}
}

func TestPDFVisionPagesAndOrder(t *testing.T) {
	r := Reader{Properties: testProperties(t)}
	calls := 0
	r.Vision = func(_ context.Context, image []byte, mime string) (string, error) {
		calls++
		if mime != "image/jpeg" || len(image) < 2 || image[0] != 0xff || image[1] != 0xd8 {
			t.Fatalf("rendered image: %s %x", mime, image)
		}
		return "scanned middle page", nil
	}
	u, e := r.Prepare(context.Background(), "x.pdf", "", pdfFixturePages("first page", "", "last page"))
	if e != nil || u.Text != "first page\n\nscanned middle page\n\nlast page" || calls != 1 {
		t.Fatalf("mixed pages: %+v %v calls=%d", u, e, calls)
	}
	u, e = r.Prepare(context.Background(), "x.pdf", "", pdfFixturePages(""))
	if e != nil || u.Text != "scanned middle page" || calls != 2 {
		t.Fatalf("scan page: %+v %v calls=%d", u, e, calls)
	}
	r.Vision = func(context.Context, []byte, string) (string, error) { return "无文本", nil }
	_, e = r.Prepare(context.Background(), "x.pdf", "", pdfFixturePages(""))
	expectBusiness(t, e, api.UnsupportedDocument, "未提取到文本")
	upstream := api.NewBusinessError(api.AIUpstreamError, "upstream")
	r.Vision = func(context.Context, []byte, string) (string, error) { return "", upstream }
	_, e = r.Prepare(context.Background(), "x.pdf", "", pdfFixturePages(""))
	if e != upstream {
		t.Fatal(e)
	}
	_, e = r.Prepare(context.Background(), "x.pdf", "", pdfFixture("", 21))
	expectBusiness(t, e, api.UnsupportedDocument, "20 页")
}
