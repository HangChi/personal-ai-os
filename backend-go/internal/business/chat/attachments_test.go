package chat

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLocalAttachmentSafetyAndContext(t *testing.T) {
	a := &Attachments{Directory: t.TempDir(), Maximum: 10 << 20}
	ctx := context.Background()
	file, e := a.Store(ctx, []byte("<hello>"), ptr("notes.txt"), ptr("text/plain"))
	if e != nil {
		t.Fatal(e)
	}
	file.Size = 99999
	v, e := a.Build(ctx, []Attachment{file, {ID: ptr("../../bad")}})
	if e != nil || len(v.Verified) != 1 || v.Verified[0].Size != 7 || !strings.Contains(v.Text, "<hello>") {
		t.Fatal(v, e)
	}
	for _, id := range []string{"../file", "------------------------------------", strings.Repeat("a", 36)} {
		if _, e = a.Read(ctx, id); e == nil {
			t.Fatal("invalid ID", id)
		}
	}
	outside := filepath.Join(t.TempDir(), "secret")
	_ = os.WriteFile(outside, []byte("secret"), 0600)
	id := "00000000-0000-0000-0000-000000000001"
	_ = os.Symlink(outside, filepath.Join(a.Directory, id))
	if _, e = a.Read(ctx, id); e == nil {
		t.Fatal("symlink read")
	}
	if _, e = a.Store(ctx, nil, nil, nil); e == nil {
		t.Fatal("empty accepted")
	}
	a.Maximum = 3
	if _, e = a.Read(ctx, *file.ID); e == nil {
		t.Fatal("oversized object read")
	}
	if _, e = a.Store(ctx, []byte("four"), nil, nil); e == nil {
		t.Fatal("oversized upload")
	}
}
func TestAttachmentDOCXImagesAndTruncation(t *testing.T) {
	a := &Attachments{Directory: t.TempDir(), Maximum: 10 << 20}
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	w, _ := z.Create("word/document.xml")
	_, _ = w.Write([]byte(`<w:document xmlns:w="urn:test"><w:body><w:p><w:r><w:t>hello</w:t><w:tab/><w:t>world</w:t></w:r></w:p></w:body></w:document>`))
	_ = z.Close()
	text, ok := a.extract(context.Background(), b.Bytes(), "test.docx", "")
	if !ok || text != "hello\tworld" {
		t.Fatal(text, ok)
	}
	if _, ok = a.extract(context.Background(), []byte("bad"), "test.docx", ""); ok {
		t.Fatal("invalid zip accepted")
	}
	files := []Attachment{}
	for i := 0; i < 6; i++ {
		f, e := a.Store(context.Background(), []byte("image"), ptr("image.png"), ptr("image/png"))
		if e != nil {
			t.Fatal(e)
		}
		files = append(files, f)
	}
	f, _ := a.Store(context.Background(), []byte(strings.Repeat("字", 9000)), ptr("long.txt"), ptr("text/plain"))
	files = append(files, f)
	v, e := a.Build(context.Background(), files)
	if e != nil || len(v.Images) != 4 || !strings.Contains(v.Text, "截断") || !strings.Contains(v.Text, "上限（4 张）") {
		t.Fatal(v, e)
	}
}
func testPDF() []byte {
	var b bytes.Buffer
	b.WriteString("%PDF-1.4\n")
	off := []int{0}
	objects := []string{"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>", "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>", "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"}
	stream := "BT /F1 12 Tf 30 100 Td (Hello P4 PDF) Tj ET"
	objects = append(objects, fmt.Sprintf("<< /Length %d >>\nstream\n%s\nendstream", len(stream), stream))
	for i, s := range objects {
		off = append(off, b.Len())
		fmt.Fprintf(&b, "%d 0 obj\n%s\nendobj\n", i+1, s)
	}
	xref := b.Len()
	fmt.Fprintf(&b, "xref\n0 6\n0000000000 65535 f \n")
	for _, o := range off[1:] {
		fmt.Fprintf(&b, "%010d 00000 n \n", o)
	}
	fmt.Fprintf(&b, "trailer << /Size 6 /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n", xref)
	return b.Bytes()
}
func TestPDFActualExtractionAndFailure(t *testing.T) {
	text, e := ExtractPDF(context.Background(), testPDF())
	if e != nil || text != "Hello P4 PDF" {
		t.Fatal("requires actual Poppler installation", text, e)
	}
	if _, e = ExtractPDF(context.Background(), []byte("%PDF-bad")); e == nil {
		t.Fatal("invalid pdf accepted")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, e = ExtractPDF(ctx, testPDF()); e == nil {
		t.Fatal("cancel ignored")
	}
}
