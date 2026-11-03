package httpmiddleware

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
)

func binaryMultipart(data []byte) []byte {
	return append(append([]byte("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"../private.bin\"\r\n\r\n"), data...), []byte("\r\n--boundary--\r\n")...)
}

func TestMultipartBinaryTemporaryLifecycle(t *testing.T) {
	directory := t.TempDir()
	data := []byte{0, 255, 13, 10, 128, 1}
	r := httptest.NewRequest("POST", "/fixture", bytes.NewReader(binaryMultipart(data)))
	r.Header.Set("Content-Type", "multipart/form-data; boundary=boundary")
	upload, err := ParseMultipart(httptest.NewRecorder(), r, MultipartLimits{64, 1024, directory})
	if err != nil {
		t.Fatal(err)
	}
	file := upload.Files["file"][0]
	if file.Filename != "../private.bin" || file.ContentType != nil || file.Size != int64(len(data)) || !strings.HasPrefix(file.path, directory+string(os.PathSeparator)) {
		t.Fatal("元数据或受控暂存路径错误")
	}
	reader, err := file.Open()
	if err != nil {
		t.Fatal(err)
	}
	got, err := io.ReadAll(reader)
	reader.Close()
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("二进制内容被转换", err)
	}
	if err := upload.Close(); err != nil {
		t.Fatal(err)
	}
	if err := upload.Close(); err != nil {
		t.Fatal("清理不幂等", err)
	}
	if _, err := file.Open(); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("暂存文件未移除")
	}
	entries, err := os.ReadDir(directory)
	if err != nil || len(entries) != 0 {
		t.Fatal("请求目录未清理", err)
	}
}

func TestMultipartChunkedLimitAndCancellationCleanTemporaryFiles(t *testing.T) {
	directory := t.TempDir()
	for _, test := range []struct {
		name   string
		body   []byte
		cancel bool
	}{
		{"file-over", binaryMultipart(bytes.Repeat([]byte("x"), 65)), false},
		{"chunked-tail-over", append(binaryMultipart([]byte("hello")), bytes.Repeat([]byte("x"), 1024)...), false},
		{"truncated-after-spool", []byte("--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"secret.bin\"\r\n\r\nprivate"), false},
		{"cancel-before", binaryMultipart([]byte("hello")), true},
	} {
		t.Run(test.name, func(t *testing.T) {
			r := httptest.NewRequest("POST", "/fixture", bytes.NewReader(test.body))
			r.ContentLength = -1
			r.Header.Set("Content-Type", "multipart/form-data; boundary=boundary")
			if test.cancel {
				ctx, cancel := context.WithCancel(r.Context())
				cancel()
				r = r.WithContext(ctx)
			}
			upload, err := ParseMultipart(httptest.NewRecorder(), r, MultipartLimits{64, 1024, directory})
			if err == nil || upload != nil {
				t.Fatal("未拒绝不完整/超限/取消请求")
			}
			if strings.HasSuffix(test.name, "over") && classifyRequestError(err).code.HTTPStatus() != 400 {
				t.Fatal("大小异常分类丢失", err)
			}
			if test.cancel && !errors.Is(err, context.Canceled) {
				t.Fatal("取消原因丢失")
			}
			entries, readErr := os.ReadDir(directory)
			if readErr != nil || len(entries) != 0 {
				t.Fatal("失败遗留暂存文件", readErr)
			}
		})
	}
}

func TestMultipartConcurrentRequestCleanupIsIsolated(t *testing.T) {
	directory := t.TempDir()
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Go(func() {
			r := httptest.NewRequest("POST", "/fixture", bytes.NewReader(binaryMultipart([]byte("hello"))))
			r.Header.Set("Content-Type", "multipart/form-data; boundary=boundary")
			upload, err := ParseMultipart(httptest.NewRecorder(), r, MultipartLimits{64, 1024, directory})
			if err != nil {
				t.Error(err)
				return
			}
			reader, err := upload.Files["file"][0].Open()
			if err != nil {
				t.Error("其他请求删除了本请求文件", err)
			} else {
				reader.Close()
			}
			if err := upload.Close(); err != nil {
				t.Error(err)
			}
		})
	}
	wg.Wait()
	entries, err := os.ReadDir(directory)
	if err != nil || len(entries) != 0 {
		t.Fatal("并发请求未全部清理", err)
	}
}
