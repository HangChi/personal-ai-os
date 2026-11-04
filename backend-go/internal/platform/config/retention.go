package config

import "errors"

type RetentionProperties struct {
	TokenUsageDays, OperationRecordDays int
	Cron                                string
}

// Retention 保持默认永久保留，数值使用现有 Spring Integer 绑定规则。
func (p *Properties) Retention() (RetentionProperties, error) {
	result := RetentionProperties{Cron: "0 30 4 * * *"}
	for _, entry := range []struct {
		key string
		dst *int
	}{
		{"app.retention.token-usage-days", &result.TokenUsageDays},
		{"app.retention.operation-record-days", &result.OperationRecordDays},
	} {
		value, found, err := p.Get(entry.key)
		if err != nil {
			return result, err
		}
		if found {
			n, err := bindJavaInteger(value, entry.key, 32)
			if err != nil {
				return result, err
			}
			if n < 0 {
				return result, errors.New("数据保留天数不能为负数（0 表示永久保留）")
			}
			*entry.dst = int(n)
		}
	}
	if value, found, err := p.Get("app.retention.cron"); err != nil {
		return result, err
	} else if found {
		result.Cron = value
	}
	return result, nil
}
