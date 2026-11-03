package config

import (
	"errors"
	"os"
	"path/filepath"
)

type MultipartProperties struct {
	MaxFileBytes, MaxRequestBytes int64
	Location                      string
}

// Multipart读取解析后的canonical属性；未指定时采用Boot默认值，内置application仍覆盖为8MB/10MB。
// Java的-1无界大小在Go基础模式明确拒绝，不静默截断。
func (p *Properties) Multipart() (MultipartProperties, error) {
	if p == nil {
		return MultipartProperties{}, errors.New("multipart配置来源不能为空")
	}
	result := MultipartProperties{MaxFileBytes: 1 << 20, MaxRequestBytes: 10 << 20}
	if value, found, err := p.Get("spring.servlet.multipart.location"); err != nil {
		return result, err
	} else if found && value != "" {
		info, err := os.Stat(value)
		if !filepath.IsAbs(value) || err != nil || !info.IsDir() {
			return result, &propertyError{"spring.servlet.multipart.location", ErrInvalidProperty}
		}
		result.Location = value
	}
	// 基础实现统一落盘，不静默接受改变存储/解析时机的扩展配置。
	for key, allowed := range map[string]string{"spring.servlet.multipart.file-size-threshold": "0B", "spring.servlet.multipart.enabled": "true", "spring.servlet.multipart.resolve-lazily": "false", "spring.servlet.multipart.strict-servlet-compliance": "false"} {
		value, found, err := p.Get(key)
		if err != nil {
			return result, err
		}
		if found {
			valid := value == allowed
			if key == "spring.servlet.multipart.file-size-threshold" {
				size, err := ParseDataSize(value)
				valid = err == nil && size != nil && *size == 0
			}
			if !valid {
				return result, &propertyError{key, ErrInvalidProperty}
			}
		}
	}
	for _, item := range []struct {
		key    string
		target *int64
	}{{"spring.servlet.multipart.max-file-size", &result.MaxFileBytes}, {"spring.servlet.multipart.max-request-size", &result.MaxRequestBytes}} {
		value, found, err := p.Get(item.key)
		if err != nil {
			return MultipartProperties{}, err
		}
		if !found {
			continue
		}
		size, err := ParseDataSize(value)
		if err != nil || size != nil && (*size <= 0 || *size == 1<<63-1) {
			return MultipartProperties{}, &propertyError{item.key, ErrInvalidProperty}
		}
		if size != nil {
			*item.target = *size
		}
	}
	return result, nil
}
