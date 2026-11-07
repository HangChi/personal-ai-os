package com.personalos.knowledge.splitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChunkingSharedContractTest {
    @Test
    void sharedChunksPreserveStructureOverlapAndSource() throws Exception {
        var cases = new ObjectMapper().readTree(Files.readString(
                Path.of("../testdata/agent-rag/chunking.json"))).path("cases");
        assertThat(cases.size()).isPositive();
        for (var scenario : cases) {
            var splitter = new MarkdownStructureTextSplitter(scenario.path("target").asInt(),
                    scenario.path("minimum").asInt(), scenario.path("overlap").asInt());
            var expected = new ArrayList<String>();
            scenario.path("expected").forEach(value -> expected.add(value.asText()));
            assertThat(splitter.splitText(scenario.path("text").asText()))
                    .as(scenario.path("name").asText()).containsExactlyElementsOf(expected);
        }
    }
}
