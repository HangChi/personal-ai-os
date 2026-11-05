package chat

import (
	"personal-ai-os/backend-go/internal/business/agent"
	"personal-ai-os/backend-go/internal/business/knowledge"
)

// The citation snapshot table is shared with Java. A reserved, non-UUID document ID
// denotes a web snapshot whose text column stores the URL followed by the excerpt.
const webCitationDocumentID = "web"

type turnCitationCollector struct {
	citations []Citation
}

func (w *turnCitationCollector) add(sources []agent.WebSource) []agent.WebSource {
	numbered := make([]agent.WebSource, 0, len(sources))
	type passage struct{ url, content string }
	seen := make(map[passage]Citation, len(w.citations))
	for _, c := range w.citations {
		if c.URL != "" {
			seen[passage{c.URL, c.Content}] = c
		}
	}
	for _, source := range sources {
		c, exists := seen[passage{source.URL, source.Content}]
		if !exists {
			if len(seen) >= 20 || len(w.citations) >= 60 {
				continue
			}
			c = Citation{Index: len(w.citations) + 1, DocumentID: webCitationDocumentID,
				DocumentTitle: ptr(source.Title), Filename: ptr(source.Host), Content: source.Content, URL: source.URL}
			w.citations = append(w.citations, c)
			seen[passage{c.URL, c.Content}] = c
		}
		numbered = append(numbered, agent.WebSource{Index: c.Index, Title: *c.DocumentTitle, Content: c.Content, URL: c.URL})
	}
	return numbered
}

func (w *turnCitationCollector) addKnowledge(hits []knowledge.Hit) []knowledge.Citation {
	numbered := make([]knowledge.Citation, 0, len(hits))
	for _, hit := range hits {
		index := 0
		for i, c := range w.citations {
			if c.URL == "" && c.DocumentID == hit.DocumentID && c.ChunkIndex == hit.ChunkIndex && c.Content == hit.Content {
				index = i + 1
				break
			}
		}
		if index == 0 {
			if len(w.citations) >= 60 {
				continue
			}
			index = len(w.citations) + 1
			w.citations = append(w.citations, Citation{Index: index, DocumentID: hit.DocumentID,
				DocumentTitle: ptr(hit.DocumentTitle), Filename: ptr(hit.Filename), ChunkIndex: hit.ChunkIndex, Content: hit.Content})
		}
		numbered = append(numbered, knowledge.CitationFromHit(index, hit))
	}
	return numbered
}
