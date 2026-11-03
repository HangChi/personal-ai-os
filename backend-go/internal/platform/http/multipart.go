package httpmiddleware

import (
	"errors"
	"io"
	"math"
	"mime"
	"mime/multipart"
	"net/http"
	"os"
	"sync"
)

type MultipartLimits struct {
	MaxFileBytes, MaxRequestBytes int64
	// TempDirectory由调用方显式指定；空值使用操作系统临时目录，不使用原文件名作为路径。
	TempDirectory string
}

type UploadFile struct {
	Filename    string
	ContentType *string
	Size        int64
	path        string
}

func (*UploadFile) String() string { return "UploadFile{redacted}" }

func (file *UploadFile) Open() (io.ReadCloser, error) { return os.Open(file.path) }

// MultipartUpload由请求调用方持有，成功/拒绝/取消后都必须Close；已打开的文件先关闭。
type MultipartUpload struct {
	Files  map[string][]*UploadFile
	Values map[string][]string
	dir    string
	once   sync.Once
	err    error
}

func (*MultipartUpload) String() string { return "MultipartUpload{redacted}" }

func (upload *MultipartUpload) Close() error {
	upload.once.Do(func() { upload.err = os.RemoveAll(upload.dir) })
	return upload.err
}

func multipartFailure(err error) error {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		return UploadTooLarge(err)
	}
	return err
}

// ParseMultipart只解析显式multipart请求；不注册业务路由或持久化附件。
// 使用原始Disposition保留空filename/Unicode/路径文本；Go原生FileName会去掉路径且将空filename当普通字段。
// 原文件名仅是待业务校验的元数据，磁盘路径全部由CreateTemp生成。
func ParseMultipart(w http.ResponseWriter, r *http.Request, limits MultipartLimits) (_ *MultipartUpload, err error) {
	if limits.MaxFileBytes <= 0 || limits.MaxRequestBytes <= 0 || limits.MaxFileBytes == math.MaxInt64 || limits.MaxRequestBytes == math.MaxInt64 {
		return nil, errors.New("multipart必须显式提供有界正数限制")
	}
	media, params, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "multipart/form-data" || params["boundary"] == "" {
		return nil, errors.New("multipart请求格式无效")
	}
	if r.ContentLength > limits.MaxRequestBytes {
		return nil, UploadTooLarge(nil)
	}
	if err := r.Context().Err(); err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp(limits.TempDirectory, "personalos-go-upload-")
	if err != nil {
		return nil, errors.New("无法创建上传临时目录")
	}
	upload := &MultipartUpload{Files: map[string][]*UploadFile{}, Values: map[string][]string{}, dir: dir}
	defer func() {
		if err != nil {
			if cleanupErr := upload.Close(); cleanupErr != nil {
				err = errors.Join(err, cleanupErr)
			}
		}
	}()
	body := http.MaxBytesReader(w, r.Body, limits.MaxRequestBytes)
	reader := multipart.NewReader(body, params["boundary"])
	for count := 0; ; count++ {
		if err := r.Context().Err(); err != nil {
			return nil, err
		}
		part, err := reader.NextRawPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, multipartFailure(err)
		}
		// 固定Tomcat 11.0.24默认50片/512字节分片头，不允许基础模式静默扩大。
		if count >= 50 {
			return nil, UploadTooLarge(nil)
		}
		headerBytes := 2
		for key, values := range part.Header {
			for _, value := range values {
				headerBytes += len(key) + 2 + len(value) + 2
			}
		}
		if headerBytes > 512 {
			return nil, UploadTooLarge(nil)
		}
		_, disposition, err := mime.ParseMediaType(part.Header.Get("Content-Disposition"))
		if err != nil {
			return nil, errors.New("multipart分片头无效")
		}
		name := disposition["name"]
		filename, isFile := disposition["filename"]
		if isFile {
			file, err := os.CreateTemp(dir, "part-")
			if err != nil {
				return nil, errors.New("无法创建上传临时文件")
			}
			size, copyErr := io.Copy(file, io.LimitReader(part, limits.MaxFileBytes+1))
			closeErr := file.Close()
			if copyErr != nil || closeErr != nil {
				return nil, multipartFailure(errors.Join(copyErr, closeErr))
			}
			if size > limits.MaxFileBytes {
				return nil, UploadTooLarge(nil)
			}
			var contentType *string
			if value := part.Header.Get("Content-Type"); value != "" {
				contentType = &value
			}
			upload.Files[name] = append(upload.Files[name], &UploadFile{Filename: filename, ContentType: contentType, Size: size, path: file.Name()})
		} else {
			// 真实Tomcat同样将max-file-size用于普通字段分片。
			data, err := io.ReadAll(io.LimitReader(part, limits.MaxFileBytes+1))
			if err != nil {
				return nil, multipartFailure(err)
			}
			if int64(len(data)) > limits.MaxFileBytes {
				return nil, UploadTooLarge(nil)
			}
			upload.Values[name] = append(upload.Values[name], string(data))
		}
		if err := part.Close(); err != nil {
			return nil, multipartFailure(err)
		}
	}
	// 将关闭boundary后的尾随字节也计入总请求限制，覆盖未知Content-Length的分块请求。
	if _, err := io.Copy(io.Discard, body); err != nil {
		return nil, multipartFailure(err)
	}
	if err := r.Context().Err(); err != nil {
		return nil, err
	}
	return upload, nil
}
