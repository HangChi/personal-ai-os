package knowledge

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

type oracleDocument struct {
	ID, Title, Filename, Content string
	Score                        float64
}
type oracleRecall struct {
	ID, Query, ExpectedSource string
	Documents                 []oracleDocument
}
type oracleFixture struct {
	Inputs struct {
		Filenames []*string
		Queries   []string
		Splits    []struct {
			Text                     string
			Target, Minimum, Overlap int
		}
		Answers []string
		Recalls []oracleRecall
	}
	Expected struct {
		Filenames []string
		Keywords  [][]string
		Splits    [][]string
		Citations [][]Citation
		Recalls   []struct {
			IDs       []string
			Citations []Citation
		}
	}
}

func readOracle(t *testing.T) oracleFixture {
	t.Helper()
	b, e := os.ReadFile("testdata/java-p6.json")
	if e != nil {
		t.Fatal(e)
	}
	var f oracleFixture
	if e = json.Unmarshal(b, &f); e != nil {
		t.Fatal(e)
	}
	return f
}
func TestJavaP6FilenameKeywordSplitterAndCitationOracle(t *testing.T) {
	f := readOracle(t)
	for i, input := range f.Inputs.Filenames {
		s := ""
		if input != nil {
			s = *input
		}
		if got := SanitizeFilename(s); got != f.Expected.Filenames[i] {
			t.Fatalf("filename %d: %q != %q", i, got, f.Expected.Filenames[i])
		}
	}
	for i, q := range f.Inputs.Queries {
		if got := ExtractKeywords(q); !reflect.DeepEqual(got, f.Expected.Keywords[i]) {
			t.Fatalf("keywords %q: %v != %v", q, got, f.Expected.Keywords[i])
		}
	}
	for i, c := range f.Inputs.Splits {
		if got := SplitMarkdown(c.Text, c.Target, c.Minimum, c.Overlap); !reflect.DeepEqual(got, f.Expected.Splits[i]) {
			t.Fatalf("split %d: %q != %q", i, got, f.Expected.Splits[i])
		}
	}
	a, b := .9, .8
	hits := []Hit{{DocumentID: "11111111-1111-4111-8111-111111111111", DocumentTitle: "A", Filename: "a.md", ChunkIndex: 0, Content: "原文证据一", Score: &a}, {DocumentID: "22222222-2222-4222-8222-222222222222", DocumentTitle: "B", Filename: "b.md", ChunkIndex: 1, Content: "原文证据二", Score: &b}}
	for i, answer := range f.Inputs.Answers {
		if got := BuildCitations(answer, hits); !reflect.DeepEqual(got, f.Expected.Citations[i]) {
			t.Fatalf("citations %d: %+v != %+v", i, got, f.Expected.Citations[i])
		}
	}
}
