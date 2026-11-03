package config

import "strings"

// Prefix 仅展开显式属性源中的已知前缀，用于extra-body任意厂商字段。
// 不枚举宿主环境或访问文件；值保持Spring属性绑定后的字符串类型。
func (p *Properties) Prefix(prefix string) (map[string]string, error) {
	out := map[string]string{}
	for _, source := range p.sources {
		for key := range source.Values {
			if strings.HasPrefix(key, prefix) {
				name := strings.TrimPrefix(key, prefix)
				if name == "" {
					continue
				}
				v, _, err := p.Get(key)
				if err != nil {
					return nil, err
				}
				out[name] = v
			}
		}
	}
	return out, nil
}
