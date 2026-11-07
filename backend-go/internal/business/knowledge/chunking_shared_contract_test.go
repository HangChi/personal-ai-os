package knowledge

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

func TestSharedChunkingStructureOverlapAndSource(t *testing.T) {
	var fixture struct {
		Cases []struct {
			Name, Text               string
			Target, Minimum, Overlap int
			Expected                 []string
		}
	}
	raw, err := os.ReadFile("../../../../testdata/agent-rag/chunking.json")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) == 0 {
		t.Fatal("empty shared contract")
	}
	for _, scenario := range fixture.Cases {
		t.Run(scenario.Name, func(t *testing.T) {
			got := SplitMarkdown(scenario.Text, scenario.Target, scenario.Minimum, scenario.Overlap)
			if !reflect.DeepEqual(got, scenario.Expected) {
				t.Fatalf("chunks=%q want=%q", got, scenario.Expected)
			}
		})
	}
}
