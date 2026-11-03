package chatcontext

import (
	"encoding/json"
	"os"
	"strings"
	"testing"
)

func TestOriginalJavaTokenFixture(t *testing.T) {
	raw, err := os.ReadFile("testdata/java-tokens.json")
	if err != nil {
		t.Fatal(err)
	}
	var fixture struct {
		Cases    []struct{ Text, Model string }
		Expected []struct{ Tokens, Estimated int }
	}
	if err = json.Unmarshal(raw, &fixture); err != nil {
		t.Fatal(err)
	}
	if len(fixture.Cases) != len(fixture.Expected) || len(fixture.Cases) < 1000 {
		t.Fatal("invalid fixture")
	}
	failures := 0
	for i, c := range fixture.Cases {
		got, estimated := CountText(c.Text, c.Model), ContentTokens(c.Text)
		want := fixture.Expected[i]
		if got != want.Tokens || estimated != want.Estimated {
			t.Errorf("case %d %s %q: tokens %d/%d estimated %d/%d", i, c.Model, c.Text, got, want.Tokens, estimated, want.Estimated)
			failures++
			if failures >= 10 {
				t.Fatal("too many differences")
			}
		}
	}
}

func TestTokenEstimationJavaUnits(t *testing.T) {
	if ContentTokens("hello world") != 2 || ContentTokens("中文") != 1 || ContentTokens("😀") != 1 || ContentTokens("") != 0 {
		t.Fatal("UTF-16/content estimation differs")
	}
	for _, model := range []string{"gpt-4", "gpt-4o", "qwen-fixture", "text-davinci-003", "davinci"} {
		if CountText("hello world", model) != 2 || CountText("", model) != 0 {
			t.Fatal(model)
		}
		if CountText(strings.Repeat("hello ", 10000), model) <= 0 {
			t.Fatal("long input lost", model)
		}
	}
}

func TestCompactSchemaPreservesStringWhitespace(t *testing.T) {
	if got := CompactSchemaChars(" {\n \"x\": \"a b\\\" c\", \"表情\": \"😀\" }"); got != JavaLength(`{"x":"a b\" c","表情":"😀"}`) {
		t.Fatal(got)
	}
}
