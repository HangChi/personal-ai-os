package memory

import (
	"encoding/json"
	"os"
	"slices"
	"testing"
)

func TestJavaFactParserOracle(t *testing.T) {
	data, err := os.ReadFile("testdata/java-facts.json")
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Raw      string
		Limit    int
		Expected []string
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	for _, test := range cases {
		if got := parseFacts(test.Raw, test.Limit); !slices.Equal(got, test.Expected) {
			t.Errorf("%q (limit %d): got %v, want %v", test.Raw, test.Limit, got, test.Expected)
		}
	}
}
