package com.personalos.knowledge.rag;

import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 固定结构化响应，执行原管线与精审，不启动服务或调用外部模型。 */
public final class RagPipelineOracle {
    public static void main(String[] args) throws Exception {
        var json = JsonMapper.builder().build();
        var output = new ArrayList<Object>();
        for (var row : json.readTree(System.in)) {
            var hits = new ArrayList<KnowledgeHitDto>();
            for (var h : row.get("hits")) {
                hits.add(new KnowledgeHitDto(UUID.fromString(h.get("documentId").asString()),
                    h.get("documentTitle").asString(), "fixture.md", 0, h.get("content").asString(), 0.9));
            }
            var recall = mock(KnowledgeRetrievalService.class);
            when(recall.hasContent()).thenReturn(true);
            when(recall.candidates(anyString(), anyInt())).thenReturn(hits);
            var model = mock(RagModelGateway.class);
            when(model.call(anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
                String rules = invocation.getArgument(0);
                return row.get(rules.contains("queries") ? "rewrite" : rules.contains("order") ? "rerank" : "evidence");
            });
            var properties = new KnowledgeProperties(1024, 4, 0.3, 5242880, List.of("md"), 10, 1200, 0, true, 60, 1.0);
            var meters = new SimpleMeterRegistry();
            try {
                var pipeline = new RagRetrievalPipeline(recall, model, new EvidenceReview(model, json),
                    properties, json, meters, true, 20000);
                var result = pipeline.retrieve(row.get("query").asString(), row.get("topK").asInt());
                output.add(Map.of("status", result.status(), "queries", result.queries(),
                    "hits", result.hits().stream().map(h -> h.documentId() + ":" + h.chunkIndex()).toList(),
                    "warnings", result.warnings(), "clarification", result.clarification(),
                    "stages", result.stages().stream().map(s -> Map.of("name", s.name(), "status", s.status(),
                        "inputCount", s.inputCount(), "outputCount", s.outputCount())).toList()));
            } finally {
                meters.close();
            }
        }
        System.out.println("ORACLE " + json.writeValueAsString(output));
    }
}
