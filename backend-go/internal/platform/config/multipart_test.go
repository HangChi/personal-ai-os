package config

import "testing"

func TestMultipartBaselineAndExplicitLimits(t *testing.T) {
	for _, test := range []struct {
		values  map[string]string
		file    int64
		request int64
		invalid bool
	}{
		{nil, 8 << 20, 10 << 20, false},
		{map[string]string{"spring.servlet.multipart.max-file-size": "64B", "spring.servlet.multipart.max-request-size": "1KB"}, 64, 1024, false},
		{map[string]string{"spring.servlet.multipart.max-file-size": "-1"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.max-file-size": "0B"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.max-request-size": "9223372036854775807B"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.max-request-size": "large"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.file-size-threshold": "0"}, 8 << 20, 10 << 20, false},
		{map[string]string{"spring.servlet.multipart.file-size-threshold": "1KB"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.location": "relative/private"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.enabled": "false"}, 0, 0, true},
		{map[string]string{"spring.servlet.multipart.resolve-lazily": "true"}, 0, 0, true},
	} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: test.values})
		if err != nil {
			t.Fatal(err)
		}
		limits, err := p.Multipart()
		if (err != nil) != test.invalid || !test.invalid && (limits.MaxFileBytes != test.file || limits.MaxRequestBytes != test.request) {
			t.Fatal("multipart大小绑定错误", limits, err)
		}
	}
	var p *Properties
	if _, err := p.Multipart(); err == nil {
		t.Fatal("不能忽略空配置")
	}
}

func TestMultipartExplicitLocation(t *testing.T) {
	dir := t.TempDir()
	p, err := LoadBaselineProperties(PropertyOptions{Overrides: map[string]string{"spring.servlet.multipart.location": dir}})
	if err != nil {
		t.Fatal(err)
	}
	got, err := p.Multipart()
	if err != nil || got.Location != dir {
		t.Fatal("显式目录未接线", err)
	}
}
