package chat

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type attachmentObjectStore struct {
	ObjectStore
	data           map[string][]byte
	err            error
	putID, putType string
	reads, deletes int
	failAfter      int
}

func (s *attachmentObjectStore) Put(_ context.Context, id string, data []byte, typ string) error {
	s.putID, s.putType = id, typ
	if s.err != nil {
		return s.err
	}
	s.data[id] = append([]byte(nil), data...)
	return nil
}
func (s *attachmentObjectStore) Read(_ context.Context, id string) ([]byte, bool, error) {
	s.reads++
	if s.err != nil && (s.failAfter == 0 || s.reads > s.failAfter) {
		return nil, false, s.err
	}
	data, found := s.data[id]
	return data, found, nil
}
func (s *attachmentObjectStore) Delete(_ context.Context, id string) error {
	s.deletes++
	delete(s.data, id)
	return s.err
}

func TestAttachmentRemoteRoundtripVerifiesActualSizeAndDefaults(t *testing.T) {
	ctx := context.Background()
	remote := &attachmentObjectStore{data: map[string][]byte{}}
	a := &Attachments{Directory: filepath.Join(t.TempDir(), "not-created"), Maximum: 100, Remote: remote}
	file, err := a.Store(ctx, []byte("fixture"), nil, nil)
	if err != nil || !attachmentID.MatchString(value(file.ID)) || value(file.Filename) != value(file.ID) || remote.putType != "application/octet-stream" {
		t.Fatal(file, err)
	}
	if _, err = os.Stat(a.Directory); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("remote store created local root", err)
	}
	file.Size = 99999
	missing := Attachment{ID: ptr("00000000-0000-0000-0000-000000000002")}
	verified, err := a.Verify(ctx, []Attachment{{}, missing, file})
	if err != nil || len(verified) != 1 || verified[0].Size != 7 || file.Size != 99999 {
		t.Fatal(verified, err)
	}
	a.delete(ctx, "../../outside")
	if remote.deletes != 0 {
		t.Fatal("invalid deletion reached object store")
	}
	a.delete(ctx, *file.ID)
	if remote.deletes != 1 || len(remote.data) != 0 {
		t.Fatal("remote deletion failed")
	}
	if _, err = a.Read(ctx, *file.ID); err == nil {
		t.Fatal("missing remote object accepted")
	}
}

func TestAttachmentRemoteFailuresAndLocalPriority(t *testing.T) {
	ctx := context.Background()
	failure := errors.New("synthetic object failure")
	remote := &attachmentObjectStore{data: map[string][]byte{}, err: failure}
	a := &Attachments{Directory: t.TempDir(), Maximum: 10, Remote: remote}
	if _, err := a.Store(ctx, []byte("fixture"), ptr("f.txt"), ptr("text/plain")); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	id := "00000000-0000-0000-0000-000000000003"
	if _, err := a.Verify(ctx, []Attachment{{ID: &id}}); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	if _, err := a.Build(ctx, []Attachment{{ID: &id}}); !errors.Is(err, failure) {
		t.Fatal(err)
	}
	remote.err = nil
	remote.data[id] = []byte(strings.Repeat("x", 11))
	if _, err := a.Read(ctx, id); err == nil {
		t.Fatal("oversize remote response accepted")
	}
	if err := os.WriteFile(filepath.Join(a.Directory, id), []byte("local"), 0600); err != nil {
		t.Fatal(err)
	}
	before := remote.reads
	data, err := a.Read(ctx, id)
	if err != nil || string(data) != "local" || remote.reads != before {
		t.Fatal("local migration object did not win", err)
	}
	a.delete(ctx, id)
	if _, err = os.Stat(filepath.Join(a.Directory, id)); !errors.Is(err, os.ErrNotExist) {
		t.Fatal(err)
	}
}

func TestAttachmentCancelledLocalWriteIsRemovedAndBadRootFails(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	a := &Attachments{Directory: t.TempDir(), Maximum: 100}
	if _, err := a.Store(ctx, []byte("fixture"), nil, nil); !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
	files, err := os.ReadDir(a.Directory)
	if err != nil || len(files) != 0 {
		t.Fatal("cancelled upload left orphan", err)
	}
	rootFile := filepath.Join(t.TempDir(), "file")
	if err = os.WriteFile(rootFile, []byte("x"), 0600); err != nil {
		t.Fatal(err)
	}
	a.Directory = filepath.Join(rootFile, "child")
	if _, err = a.Store(context.Background(), []byte("fixture"), nil, nil); err == nil {
		t.Fatal("invalid root accepted")
	}
	if _, err = a.Read(context.Background(), "00000000-0000-0000-0000-000000000001"); err == nil {
		t.Fatal("invalid root read accepted")
	}
}

func TestAttachmentBuildReportsReadRacesUnsupportedAndInvalidImages(t *testing.T) {
	ctx := context.Background()
	id := "00000000-0000-0000-0000-000000000004"
	remote := &attachmentObjectStore{data: map[string][]byte{id: []byte("fixture")}, err: errors.New("removed between reads"), failAfter: 1}
	a := &Attachments{Directory: t.TempDir(), Maximum: 10 << 20, Remote: remote}
	v, err := a.Build(ctx, []Attachment{{ID: &id, Filename: ptr("race.txt"), ContentType: ptr("text/plain")}})
	if err != nil || !strings.Contains(v.Text, "读取失败") || len(v.Verified) != 1 {
		t.Fatal(v, err)
	}
	remote.err = nil
	for _, tc := range []struct {
		name, typ, notice string
		data              []byte
	}{
		{"unsupported.bin", "application/octet-stream", "格式不支持", []byte("fixture")},
		{"empty.txt", "text/plain", "格式不支持", []byte("   ")},
		{"bad.png", "image/png; =", "无法识别图片类型", []byte("fixture")},
		{"large.png", "image/png", "图片超过 5MB", make([]byte, 5*1024*1024+1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			remote.data[id] = tc.data
			v, err := a.Build(ctx, []Attachment{{ID: &id, Filename: &tc.name, ContentType: &tc.typ}})
			if err != nil || len(v.Images) != 0 || !strings.Contains(v.Text, tc.notice) {
				t.Fatal(v.Text, err)
			}
		})
	}
	v, err = a.Build(ctx, nil)
	if err != nil || v.Text != "" || len(v.Verified) != 0 {
		t.Fatal(v, err)
	}
}

func TestAttachmentExtractionHandlesFormatsFaultsAndPanic(t *testing.T) {
	a := &Attachments{}
	ctx := context.Background()
	for _, typ := range []string{"text/plain", "application/json", "application/xml", "application/yaml", "application/x-yaml"} {
		text, ok := a.extract(ctx, []byte{32, 97, 255, 32}, "fixture", typ)
		if !ok || text != "a�" {
			t.Fatal(typ, text, ok)
		}
	}
	for _, name := range []string{"a.md", "a.txt", "a.json", "a.csv", "a.log", "a.yaml", "a.yml", "a.xml"} {
		if text, ok := a.extract(ctx, []byte(" valid "), name, ""); !ok || text != "valid" {
			t.Fatal(name, text, ok)
		}
	}
	if _, ok := a.extract(ctx, []byte("pdf"), "a.pdf", "application/pdf"); ok {
		t.Fatal("PDF extractor not configured")
	}
	a.ExtractPDF = func(context.Context, []byte) (string, error) { return " ignored ", errors.New("parser failed") }
	if _, ok := a.extract(ctx, []byte("pdf"), "a.pdf", ""); ok {
		t.Fatal("PDF error ignored")
	}
	a.ExtractPDF = func(context.Context, []byte) (string, error) { panic("synthetic parser panic") }
	if text, ok := a.extract(ctx, nil, "a.pdf", ""); ok || text != "" {
		t.Fatal("parser panic escaped")
	}
	a.ExtractPDF = func(context.Context, []byte) (string, error) { return " text ", nil }
	if text, ok := a.extract(ctx, nil, "fixture", "application/pdf"); !ok || text != "text" {
		t.Fatal(text, ok)
	}
	for _, xml := range []string{"<w:document xmlns:w='urn:test'><w:body>", "<document><body/></document>"} {
		var b bytes.Buffer
		z := zip.NewWriter(&b)
		w, err := z.Create("word/document.xml")
		if err != nil {
			t.Fatal(err)
		}
		if _, err = w.Write([]byte(xml)); err != nil {
			t.Fatal(err)
		}
		if err = z.Close(); err != nil {
			t.Fatal(err)
		}
		if _, ok := a.extract(ctx, b.Bytes(), "a.docx", ""); ok {
			t.Fatal("broken or empty DOCX accepted")
		}
	}
	var b bytes.Buffer
	z := zip.NewWriter(&b)
	if _, err := z.Create("other.xml"); err != nil {
		t.Fatal(err)
	}
	if err := z.Close(); err != nil {
		t.Fatal(err)
	}
	if _, ok := a.extract(ctx, b.Bytes(), "a.docx", ""); ok {
		t.Fatal("DOCX missing document accepted")
	}
}
