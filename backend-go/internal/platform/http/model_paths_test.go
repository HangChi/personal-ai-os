package httpmiddleware

import (
	"encoding/json"
	"os"
	"testing"
)

func TestModelPathsAgainstActualJavaFixture(t *testing.T) {
	data, err := os.ReadFile("testdata/java-model-request-paths.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Baseline string `json:"baseline_commit"`
		Cases    []struct {
			Method  string `json:"method"`
			URI     string `json:"uri"`
			Context string `json:"context_path"`
			Matched bool   `json:"matched"`
			Error   bool   `json:"error"`
		} `json:"cases"`
	}
	if err := json.Unmarshal(data, &fixture); err != nil {
		t.Fatal(err)
	}
	if fixture.Baseline != "cb5d694d62fa93742b5789b97273e76d931ce335" || len(fixture.Cases) < 200 {
		t.Fatal("Java fixture 不完整或基线漂移")
	}
	for _, test := range fixture.Cases {
		t.Run(test.Method+" "+test.URI+" context="+test.Context, func(t *testing.T) {
			matched, err := ModelRequestPath(test.Method, test.URI, test.Context)
			if matched != test.Matched || (err != nil) != test.Error {
				t.Fatalf("Java matched=%v error=%v；Go matched=%v error=%v", test.Matched, test.Error, matched, err)
			}
		})
	}
}

func TestInvalidContextPathFailsSafely(t *testing.T) {
	if matched, err := ModelRequestPath("POST", "/api/v1/chat/uploads", "/not-mounted"); matched || err == nil {
		t.Fatal("调用方挂载前缀错误不能误判放行")
	}
}
