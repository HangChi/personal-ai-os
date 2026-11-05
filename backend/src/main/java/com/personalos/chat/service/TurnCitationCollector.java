package com.personalos.chat.service;

import com.personalos.common.ai.WebSource;
import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.knowledge.dto.CitationDto;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TurnCitationCollector {
    // Shared with Go. A reserved non-UUID document ID denotes a web snapshot.
    static final String WEB_DOCUMENT_ID = "web";

    private record Passage(String url, String content) { }

    private final Map<Passage, ChatCitationDto> web = new LinkedHashMap<>();
    private final List<ChatCitationDto> extra = new ArrayList<>();

    synchronized List<WebSource.Numbered> add(int knowledgeCount, List<WebSource> sources) {
        List<WebSource.Numbered> numbered = new ArrayList<>(sources.size());
        for (var source : sources) {
            var key = new Passage(source.url(), source.content());
            var citation = web.get(key);
            if (citation == null) {
                if (web.size() >= 20 || knowledgeCount + extra.size() >= 60) continue;
                citation = new ChatCitationDto(knowledgeCount + extra.size() + 1, WEB_DOCUMENT_ID,
                        source.title(), source.host(), 0, source.content(), source.url());
                web.put(key, citation);
                extra.add(citation);
            }
            numbered.add(new WebSource.Numbered(citation.index(), citation.documentTitle(),
                    citation.url(), citation.content()));
        }
        return numbered;
    }

    synchronized List<CitationDto> addKnowledge(List<ChatCitationDto> initial, List<KnowledgeHitDto> hits) {
        List<ChatCitationDto> candidates = new ArrayList<>(initial);
        candidates.addAll(extra);
        List<CitationDto> numbered = new ArrayList<>(hits.size());
        for (var hit : hits) {
            String id = hit.documentId() == null ? "" : hit.documentId().toString();
            var citation = candidates.stream()
                    .filter(c -> c.url() == null && id.equals(c.documentId()) && c.chunkIndex() == hit.chunkIndex()
                            && hit.content().equals(c.content()))
                    .findFirst().orElse(null);
            if (citation == null) {
                if (candidates.size() >= 60) continue;
                citation = new ChatCitationDto(candidates.size() + 1, id, hit.documentTitle(), hit.filename(),
                        hit.chunkIndex(), hit.content());
                extra.add(citation);
                candidates.add(citation);
            }
            numbered.add(CitationDto.fromHit(citation.index(), hit));
        }
        return numbered;
    }

    synchronized List<ChatCitationDto> snapshot() {
        return List.copyOf(extra);
    }
}
