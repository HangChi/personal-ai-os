package config

import "testing"

func TestP8RetentionBinding(t *testing.T) {
	for _, tc := range []struct {
		values            map[string]string
		usage, operations int
		bad               bool
	}{
		{nil, 0, 0, false},
		{map[string]string{"app.retention.token-usage-days": "180", "app.retention.operation-record-days": "30"}, 180, 30, false},
		{map[string]string{"app.retention.token-usage-days": "0x1e"}, 30, 0, false},
		{map[string]string{"app.retention.operation-record-days": "2147483647"}, 0, 2147483647, false},
		{map[string]string{"app.retention.token-usage-days": "-1"}, 0, 0, true},
		{map[string]string{"app.retention.operation-record-days": "-1"}, 0, 0, true},
		{map[string]string{"app.retention.token-usage-days": "2147483648"}, 0, 0, true},
		{map[string]string{"app.retention.token-usage-days": "secret-invalid"}, 0, 0, true},
	} {
		p, err := LoadBaselineProperties(PropertyOptions{Overrides: tc.values})
		if err != nil {
			t.Fatal(err)
		}
		r, err := p.Retention()
		if (err != nil) != tc.bad {
			t.Fatalf("bad=%t: %v", tc.bad, err)
		}
		if !tc.bad && (r.TokenUsageDays != tc.usage || r.OperationRecordDays != tc.operations || r.Cron != "0 30 4 * * *") {
			t.Fatal(r)
		}
	}
}
