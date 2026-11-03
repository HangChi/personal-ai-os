package config

import "errors"

type MultipartProperties struct {
	MaxFileBytes, MaxRequestBytes int64
}

// Multipart读取解析后的canonical属性；未指定时采用Boot默认值，内置application仍覆盖为8MB/10MB。
// Java的-1无界大小在Go基础模式明确拒绝，不静默截断。
func (p *Properties) Multipart() (MultipartProperties, error) {
	if p == nil {
		return MultipartProperties{}, errors.New("multipart配置来源不能为空")
	}
	result := MultipartProperties{MaxFileBytes: 1 << 20, MaxRequestBytes: 10 << 20}
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
