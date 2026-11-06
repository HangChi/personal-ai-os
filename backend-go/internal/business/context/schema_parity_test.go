package chatcontext

import (
	"encoding/json"
	"os"
	"testing"
)

func TestFixedJavaToolSchemaCounts(t *testing.T) {
	raw, err := os.ReadFile("testdata/java-tool-schemas.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Tools []struct {
			Name      string
			Schema    string
			RawTokens int `json:"raw_tokens"`
		}
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Tools) != 23 {
		t.Fatalf("expected 23 local chat tools, got %d", len(fixture.Tools))
	}
	rawTotal, compactTotal := 0, 0
	for _, tool := range fixture.Tools {
		if got := CountText(tool.Schema, "mock-model"); got != tool.RawTokens {
			t.Errorf("%s raw tokens = %d, Java = %d", tool.Name, got, tool.RawTokens)
		}
		var parameters map[string]any
		if err := json.Unmarshal([]byte(tool.Schema), &parameters); err != nil {
			t.Fatal(err)
		}
		compact, err := json.Marshal(parameters)
		if err != nil {
			t.Fatal(err)
		}
		rawTotal += tool.RawTokens
		compactTotal += CountText(string(compact), "mock-model")
	}
	if rawTotal <= compactTotal {
		t.Fatal("expected raw Java formatting to consume more tokens than compact Go schemas")
	}
	t.Logf("23 local chat schema tokens: raw Java=%d; compact Go=%d; formatting delta=%d", rawTotal, compactTotal, rawTotal-compactTotal)
}
