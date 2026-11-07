package com.personalos.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.chat.dto.ChatCitationDto;
import com.personalos.common.ai.WebSource;
import com.personalos.knowledge.dto.CitationDto;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TurnCitationCollectorTest {
    @Test
    void keepsStableNumbersAndUsesSpecificProviderSources() {
        var web = WebSearchTools.parseSources("""
                {"grounding":{"generic":[
                  {"title":"页面","url":"https://example.com/reports/2026","snippets":["第一段","第二段"]},
                  {"url":"javascript:alert(1)","snippets":["invalid"]},
                  {"url":"https://example.com/no-excerpt","snippets":[]}
                ]}}
                """, new ObjectMapper());
        assertThat(web).containsExactly(new WebSource(
                "页面", "https://example.com/reports/2026", "第一段\n\n第二段", "example.com"));

        var firstDocument = UUID.randomUUID();
        var initial = List.of(new ChatCitationDto(1, firstDocument.toString(), "制度", "rules.md", 0, "原文"));
        var hits = List.of(new KnowledgeHitDto(firstDocument, "制度", "rules.md", 0, "原文", 0.8),
                new KnowledgeHitDto(UUID.randomUUID(), "资料", "other.md", 1, "另一段", 0.7));
        var collector = new TurnCitationCollector();
        assertThat(collector.add(1, web)).extracting(WebSource.Numbered::index).containsExactly(2);
        assertThat(collector.addKnowledge(initial, hits)).extracting(CitationDto::index).containsExactly(1, 3);
        assertThat(collector.add(1, web)).extracting(WebSource.Numbered::index).containsExactly(2);
        assertThat(collector.snapshot()).extracting(ChatCitationDto::index).containsExactly(2, 3);
    }

    @Test void preservesDistinctPassagesAndBothCitationCaps() {
        var collector = new TurnCitationCollector();
        var sources = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> new WebSource("title", "https://example.test/" + i, "body", "host")).toList();
        assertThat(collector.add(0, sources)).hasSize(20);
        assertThat(collector.add(0, List.of(sources.getFirst()))).hasSize(1);
        var full = new TurnCitationCollector();
        assertThat(full.add(60, sources)).isEmpty();
        var initial = java.util.stream.IntStream.range(0, 60)
                .mapToObj(i -> new ChatCitationDto(i + 1, "d" + i, "t", "f", 0, "c")).toList();
        assertThat(full.addKnowledge(initial, List.of(new KnowledgeHitDto(null, "t", "f", 0, "body", .5)))).isEmpty();
        var nullable = new TurnCitationCollector();
        var hit = new KnowledgeHitDto(null, "t", "f", 2, "body", .5);
        assertThat(nullable.addKnowledge(List.of(), List.of(hit, hit))).extracting(CitationDto::index).containsExactly(1, 1);
        assertThat(nullable.snapshot()).singleElement().satisfies(c -> assertThat(c.documentId()).isEmpty());
        var matches = List.of(new ChatCitationDto(1, "", "t", "f", 1, "body"),
            new ChatCitationDto(2, "", "t", "f", 2, "other"),
            new ChatCitationDto(3, "", "t", "f", 2, "body", "https://example.test"));
        assertThat(new TurnCitationCollector().addKnowledge(matches, List.of(hit))).extracting(CitationDto::index).containsExactly(4);
    }
}
