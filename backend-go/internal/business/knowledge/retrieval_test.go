package knowledge

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func TestKeywordsIdentifierStopWordsOrderAndLimit(t *testing.T) {
	for _, tt := range []struct {
		in   string
		want []string
	}{{"unknown 和 any 到底差在哪", []string{"unknown", "any"}}, {"2PC 与 3PC 和 1Panel", []string{"2pc", "3pc", "1panel"}}, {"C++ 和 C# 的区别", []string{"c++", "c#"}}, {"Object.defineProperty...", []string{"object.defineproperty"}}, {"what is the difference", []string{"difference"}}, {"how do I use it", []string{}}, {"T U 100 2026 索引失效", []string{}}, {"Kafka kafka KAFKA watch alpha beta", []string{"kafka", "watch", "alpha"}}} {
		if got := ExtractKeywords(tt.in); !reflect.DeepEqual(got, tt.want) {
			t.Fatal(tt.in, got, tt.want)
		}
	}
}
func TestMetadataNullIDAndRankingScoresStaySeparate(t *testing.T) {
	h := fromChunk(Chunk{Content: "raw", Metadata: map[string]any{"document_id": "broken", "chunk_index": "bad"}}, nil, "KEYWORD")
	b, e := json.Marshal(h)
	if e != nil || !strings.Contains(string(b), "\"documentId\":null") || !strings.Contains(string(b), "\"score\":null") || h.ChunkIndex != -1 {
		t.Fatal(string(b), h, e)
	}
	if idf(100, 1) < .8 || idf(100, 100) != 0 || idf(1, 1) != 1 {
		t.Fatal("IDF")
	}
}
