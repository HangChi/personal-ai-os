//go:build integration

package chat

import (
	"bytes"
	"context"
	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"net/url"
	"os"
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/platform/config"
	"testing"
	"time"
)

func TestS3DisposableOwnershipSizeLifecycle(t *testing.T) {
	if os.Getenv("GO_TEST_S3") != "disposable" {
		t.Skip("需要独立 MinIO runner")
	}
	endpoint := os.Getenv("GO_TEST_S3_ENDPOINT")
	u, e := url.Parse(endpoint)
	if e != nil || u.Hostname() != "127.0.0.1" {
		t.Fatal("only disposable loopback")
	}
	ctx := context.Background()
	open := func(namespace string) *S3Store {
		p, e := config.LoadBaselineProperties(config.PropertyOptions{Overrides: map[string]string{"app.storage.s3.enabled": "true", "app.storage.s3.endpoint": endpoint, "app.storage.s3.region": "us-east-1", "app.storage.s3.bucket": "p4-disposable", "app.storage.s3.namespace": namespace, "app.storage.s3.cleanup": "true", "app.storage.s3.access-key": os.Getenv("GO_TEST_S3_ACCESS"), "app.storage.s3.secret-key": os.Getenv("GO_TEST_S3_SECRET")}})
		if e != nil {
			t.Fatal(e)
		}
		remote, e := OpenS3(p, nil, 100)
		if e != nil {
			t.Fatal(e)
		}
		s := remote.(*S3Store)
		t.Cleanup(func() { _ = s.Close() })
		return s
	}
	a, b := open("namespace-a"), open("namespace-b")
	_, e = a.client.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: &a.bucket})
	if e != nil {
		t.Fatal(e)
	}
	id, orphan, foreign, oversize := agent.UUID(), agent.UUID(), agent.UUID(), agent.UUID()
	for _, x := range []string{id, orphan} {
		if e = a.Put(ctx, x, []byte("hello"), "text/plain"); e != nil {
			t.Fatal(e)
		}
	}
	data, found, e := a.Read(ctx, id)
	if e != nil || !found || string(data) != "hello" {
		t.Fatal(string(data), found, e)
	}
	if _, found, e = b.Read(ctx, id); e != nil || found {
		t.Fatal("cross namespace read", found, e)
	}
	key := a.prefix + foreign
	_, e = a.client.PutObject(ctx, &s3.PutObjectInput{Bucket: &a.bucket, Key: &key, Body: bytes.NewReader([]byte("foreign")), Metadata: map[string]string{"personalos-owner": b.owner}})
	if e != nil {
		t.Fatal(e)
	}
	if _, found, e = a.Read(ctx, foreign); e != nil || found {
		t.Fatal("foreign ownership ignored", found, e)
	}
	key = a.prefix + oversize
	_, e = a.client.PutObject(ctx, &s3.PutObjectInput{Bucket: &a.bucket, Key: &key, Body: bytes.NewReader(bytes.Repeat([]byte("x"), 101)), Metadata: map[string]string{"personalos-owner": a.owner}})
	if e != nil {
		t.Fatal(e)
	}
	if _, _, e = a.Read(ctx, oversize); e == nil {
		t.Fatal("oversized object accepted")
	}
	_, _ = a.client.DeleteObject(ctx, &s3.DeleteObjectInput{Bucket: &a.bucket, Key: &key})
	if e = a.Put(ctx, agent.UUID(), bytes.Repeat([]byte("x"), 101), "text/plain"); e == nil {
		t.Fatal("oversized upload accepted")
	}
	removed, e := a.Prune(ctx, map[string]bool{id: true}, time.Now().Add(time.Hour))
	if e != nil || removed != 1 {
		t.Fatal(removed, e)
	}
	if _, found, e = a.Read(ctx, id); e != nil || !found {
		t.Fatal("reference deleted", e)
	}
	if _, found, e = a.Read(ctx, orphan); e != nil || found {
		t.Fatal("orphan retained", e)
	}
	if e = a.Delete(ctx, id); e != nil {
		t.Fatal(e)
	}
	if _, found, e = a.Read(ctx, id); e != nil || found {
		t.Fatal("delete failed", e)
	}
	head, e := a.client.HeadObject(ctx, &s3.HeadObjectInput{Bucket: &a.bucket, Key: aws.String(a.prefix + foreign)})
	if e != nil || head.Metadata["personalos-owner"] != b.owner {
		t.Fatal("foreign object deleted", e)
	}
}
