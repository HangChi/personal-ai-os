package chat

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/smithy-go"

	"personal-ai-os/backend-go/internal/business/idempotency"
	"personal-ai-os/backend-go/internal/business/model"
	"personal-ai-os/backend-go/internal/platform/api"
	"personal-ai-os/backend-go/internal/platform/config"
)

type S3Store struct {
	client                *s3.Client
	bucket, prefix, owner string
	maximum               int64
	cleanup               bool
	mu                    sync.Mutex
	cursor                *string
	transport             *http.Transport
}

func (*S3Store) String() string               { return "S3Store{redacted}" }
func (s *S3Store) GoString() string           { return s.String() }
func (*S3Store) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func s3Unavailable() error {
	return api.NewBusinessError(api.InternalError, "附件对象存储暂不可用，请稍后重试")
}
func OpenS3(p *config.Properties, transport http.RoundTripper, maximum int64) (ObjectStore, error) {
	get := func(k string) (string, error) { v, _, e := p.Get("app.storage.s3." + k); return v, e }
	enabled, e := get("enabled")
	if e != nil {
		return nil, e
	}
	if !strings.EqualFold(enabled, "true") {
		if !strings.EqualFold(enabled, "false") && enabled != "" {
			return nil, errors.New("S3开关无效")
		}
		return nil, nil
	}
	fields := map[string]string{}
	for _, k := range []string{"endpoint", "region", "access-key", "secret-key", "bucket", "namespace", "cleanup"} {
		fields[k], e = get(k)
		if e != nil {
			return nil, e
		}
	}
	endpoint, e := url.Parse(fields["endpoint"])
	if e != nil || endpoint.Host == "" || endpoint.User != nil || endpoint.RawQuery != "" || endpoint.Fragment != "" || (endpoint.Scheme != "https" && endpoint.Scheme != "http") {
		return nil, errors.New("S3 endpoint不合法")
	}
	if !regexp.MustCompile(`^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$`).MatchString(fields["bucket"]) || strip(fields["namespace"]) == "" || strip(fields["region"]) == "" {
		return nil, errors.New("S3 bucket、namespace与region必须合法且非空")
	}
	access, secret := fields["access-key"], fields["secret-key"]
	if (strip(access) == "") != (strip(secret) == "") {
		return nil, errors.New("S3 access-key与secret-key必须同时配置")
	}
	store := &S3Store{bucket: fields["bucket"], owner: idempotency.SHA256(fields["namespace"]), maximum: maximum, cleanup: strings.EqualFold(fields["cleanup"], "true")}
	if fields["cleanup"] != "" && !strings.EqualFold(fields["cleanup"], "true") && !strings.EqualFold(fields["cleanup"], "false") {
		return nil, errors.New("S3清理开关无效")
	}
	store.prefix = "personalos/" + store.owner + "/attachments/"
	if transport == nil {
		store.transport = model.NewTransport()
		transport = store.transport
	}
	client := &http.Client{Transport: transport, Timeout: 10 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	options := []func(*awsconfig.LoadOptions) error{awsconfig.WithRegion(fields["region"]), awsconfig.WithHTTPClient(client), awsconfig.WithRetryMaxAttempts(1)}
	// 与 Java 一致：显式凭据优先；启用S3但不提供时使用官方默认凭据链。
	if strip(access) != "" {
		options = append(options, awsconfig.WithCredentialsProvider(credentials.NewStaticCredentialsProvider(access, secret, "")))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cfg, e := awsconfig.LoadDefaultConfig(ctx, options...)
	if e != nil {
		return nil, errors.New("S3凭据配置无效")
	}
	store.client = s3.NewFromConfig(cfg, func(o *s3.Options) { o.BaseEndpoint = aws.String(fields["endpoint"]); o.UsePathStyle = true })
	return store, nil
}
func (s *S3Store) key(id string) (string, error) {
	if !attachmentID.MatchString(id) {
		return "", attachmentMissing()
	}
	return s.prefix + id, nil
}
func (s *S3Store) Put(ctx context.Context, id string, data []byte, contentType string) error {
	key, e := s.key(id)
	if e != nil {
		return e
	}
	if int64(len(data)) > s.maximum {
		return invalid("附件超过大小上限")
	}
	_, e = s.client.PutObject(ctx, &s3.PutObjectInput{Bucket: &s.bucket, Key: &key, Body: bytes.NewReader(data), ContentType: &contentType, Metadata: map[string]string{"personalos-owner": s.owner}})
	if e != nil {
		return s3Unavailable()
	}
	return nil
}
func (s *S3Store) head(ctx context.Context, key string) (*s3.HeadObjectOutput, error) {
	h, e := s.client.HeadObject(ctx, &s3.HeadObjectInput{Bucket: &s.bucket, Key: &key})
	if e != nil {
		var a smithy.APIError
		if errors.As(e, &a) && (a.ErrorCode() == "NotFound" || a.ErrorCode() == "NoSuchKey") {
			return nil, nil
		}
		return nil, s3Unavailable()
	}
	if h.Metadata["personalos-owner"] != s.owner {
		return nil, nil
	}
	if aws.ToInt64(h.ContentLength) > s.maximum {
		return nil, invalid("对象超出附件大小上限")
	}
	return h, nil
}
func (s *S3Store) Read(ctx context.Context, id string) ([]byte, bool, error) {
	key, e := s.key(id)
	if e != nil {
		return nil, false, e
	}
	head, e := s.head(ctx, key)
	if e != nil || head == nil {
		return nil, false, e
	}
	v, e := s.client.GetObject(ctx, &s3.GetObjectInput{Bucket: &s.bucket, Key: &key, IfMatch: head.ETag})
	if e != nil {
		return nil, false, s3Unavailable()
	}
	defer v.Body.Close()
	if v.Metadata["personalos-owner"] != s.owner {
		return nil, false, nil
	}
	if aws.ToInt64(v.ContentLength) > s.maximum {
		return nil, false, invalid("对象超出附件大小上限")
	}
	data, e := io.ReadAll(io.LimitReader(v.Body, s.maximum+1))
	if e != nil {
		return nil, false, s3Unavailable()
	}
	if int64(len(data)) > s.maximum {
		return nil, false, invalid("对象超出附件大小上限")
	}
	return data, true, nil
}
func (s *S3Store) Delete(ctx context.Context, id string) error {
	key, e := s.key(id)
	if e != nil {
		return e
	}
	head, e := s.head(ctx, key)
	if e != nil || head == nil {
		return e
	}
	_, e = s.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: &s.bucket, Key: &key, IfMatch: head.ETag})
	if e != nil {
		return s3Unavailable()
	}
	return nil
}
func (s *S3Store) Prune(ctx context.Context, refs map[string]bool, before time.Time) (int, error) {
	if !s.cleanup {
		return 0, nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	listing, e := s.client.ListObjectsV2(ctx, &s3.ListObjectsV2Input{Bucket: &s.bucket, Prefix: &s.prefix, ContinuationToken: s.cursor, MaxKeys: aws.Int32(100)})
	if e != nil {
		return 0, s3Unavailable()
	}
	s.cursor = listing.NextContinuationToken
	removed := 0
	for _, object := range listing.Contents {
		key := aws.ToString(object.Key)
		if !strings.HasPrefix(key, s.prefix) {
			continue
		}
		id := strings.TrimPrefix(key, s.prefix)
		if !attachmentID.MatchString(id) || refs[id] || object.LastModified == nil || !object.LastModified.Before(before) {
			continue
		}
		head, e := s.head(ctx, key)
		if e != nil {
			return removed, e
		}
		if head == nil {
			continue
		}
		if e = s.Delete(ctx, id); e != nil {
			return removed, e
		}
		removed++
	}
	return removed, nil
}
func (s *S3Store) Close() error {
	if s.transport != nil {
		s.transport.CloseIdleConnections()
	}
	return nil
}
