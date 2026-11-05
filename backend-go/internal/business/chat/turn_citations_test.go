package chat

import (
	"testing"

	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/knowledge"
)

func TestTurnCitationNumbersStayStableAcrossWebAndKnowledge(t *testing.T) {
	collector := turnCitationCollector{citations: []Citation{{Index: 1, DocumentID: "doc", Content: "原文"}}}
	web := agent.WebSource{Title: "报道", URL: "https://example.com/report", Content: "网页摘录"}
	knowledgeHits := []knowledge.Hit{{DocumentID: "doc", Content: "原文"}, {DocumentID: "doc2", Content: "另一段"}}
	first := collector.add([]agent.WebSource{web})
	hits := collector.addKnowledge(knowledgeHits)
	repeated := collector.add([]agent.WebSource{web})
	if len(collector.citations) != 3 || first[0].Index != 2 || repeated[0].Index != 2 ||
		hits[0].Index != 1 || hits[1].Index != 3 || collector.citations[1].URL != web.URL {
		t.Fatal(collector, first, hits, repeated)
	}
}
