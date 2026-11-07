package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.dto.CitationDto;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class CitationAuditTest {
    static Stream<Arguments> contractCases() throws Exception {
        var fixture = new ObjectMapper().readTree(Files.readString(
                Path.of("../testdata/agent-rag/citation-audit.json")));
        var cases = new ArrayList<Arguments>();
        for (var test : fixture.path("cases")) {
            cases.add(Arguments.of(test.path("name").asString(), fixture, test));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void sharedContractRejectsInvalidClaimsWithoutReturningPartialAudit(
            String name, JsonNode fixture, JsonNode test) {
        var json = new ObjectMapper();
        var gateway = mock(RagModelGateway.class);
        var audit = new CitationAudit(gateway, new JsonCodec(json));
        String answer = test.has("answer") ? test.path("answer").asString() : fixture.path("answer").asString();
        var evidence = test.has("citations") ? test.path("citations") : fixture.path("citations");
        var citations = new ArrayList<CitationDto>();
        for (var item : evidence) {
            citations.add(citation(item.path("index").asInt(), item.path("content").asString()));
        }
        when(gateway.call(anyString(), anyString(), eq(5000L))).thenReturn(test.path("response"));

        var result = audit.check(answer, citations);

        assertThat(result.status()).as(name).isEqualTo(test.path("expectedStatus").asString());
        if ("CHECKED".equals(result.status())) {
            List<CitationAudit.Check> expected = json.convertValue(test.path("response").path("claims"),
                    json.getTypeFactory().constructCollectionType(List.class, CitationAudit.Check.class));
            assertThat(result.claims()).as(name).containsExactlyElementsOf(expected);
        } else {
            assertThat(result.claims()).as("Rejected response must not leak earlier accepted claims: %s", name).isEmpty();
        }
        verify(gateway).call(anyString(), anyString(), eq(5000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \n\t"})
    void blankAnswersDoNotSpendModelBudget(String answer) {
        var gateway = mock(RagModelGateway.class);
        var result = new CitationAudit(gateway, new JsonCodec(new ObjectMapper())).check(answer, List.of());
        assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.claims()).isEmpty();
        verifyNoInteractions(gateway);
    }

    @Test
    void unicodeLimitsCountCodepointsAndBoundEvidenceSentToTheModel() {
        var json = new ObjectMapper();
        var gateway = mock(RagModelGateway.class);
        var audit = new CitationAudit(gateway, new JsonCodec(json));
        String answer = "😀".repeat(5996) + " [1]";
        String content = "😀".repeat(1600) + "不应发送的尾部";
        when(gateway.call(anyString(), anyString(), eq(5000L))).thenReturn(json.readTree("""
                {"claims":[{"claim":"😀 [1]","status":"SUPPORTED","index":1,"quote":"😀😀😀😀"}]}
                """));
        assertThat(audit.check(answer, List.of(citation(1, content))).status()).isEqualTo("CHECKED");
        var payload = ArgumentCaptor.forClass(String.class);
        verify(gateway).call(anyString(), payload.capture(), eq(5000L));
        var sent = json.readTree(payload.getValue());
        assertThat(sent.path("answer").asString()).isEqualTo(answer);
        assertThat(sent.path("evidence").path("1").asString()).isEqualTo("😀".repeat(1600));

        var unused = mock(RagModelGateway.class);
        var rejected = new CitationAudit(unused, new JsonCodec(json)).check(answer + "😀", List.of(citation(1, content)));
        assertThat(rejected.status()).isEqualTo("UNVERIFIED");
        verifyNoInteractions(unused);
    }

    @Test
    void verifierFailureReturnsNoVerifiedClaims() {
        var gateway = mock(RagModelGateway.class);
        when(gateway.call(anyString(), anyString(), eq(5000L))).thenThrow(new IllegalStateException("synthetic timeout"));
        var result = new CitationAudit(gateway, new JsonCodec(new ObjectMapper()))
                .check("报销每天五百元 [1]。", List.of(citation(1, "报销限额每天五百元。")));
        assertThat(result.status()).isEqualTo("UNVERIFIED");
        assertThat(result.claims()).isEmpty();
    }

    private static CitationDto citation(int index, String content) {
        return new CitationDto(index, null, "制度", "policy.md", 0, content, null, null, null);
    }

    @Test
    void rejectsFabricatedEvidence() {
        ObjectMapper json = new ObjectMapper();
        RagModelGateway gateway = mock(RagModelGateway.class);
        CitationAudit audit = new CitationAudit(gateway, new JsonCodec(json));
        var citations = List.of(new CitationDto(1, null, "制度", "policy.md", 0, "报销限额每天五百元。", null, null, null));
        String answer = "报销每天五百元 [1]。";
        String result = "{\"claims\":[{\"claim\":\"" + answer + "\",\"status\":\"SUPPORTED\",\"index\":1,\"quote\":\"%s\"}]}";
        when(gateway.call(anyString(), anyString(), eq(5000L)))
                .thenReturn(json.readTree(result.formatted("每天五百元")), json.readTree(result.formatted("每天八百元")));
        assertThat(audit.check(answer, citations).status()).isEqualTo("CHECKED");
        assertThat(audit.check(answer, citations).status()).isEqualTo("UNVERIFIED");
    }
}
