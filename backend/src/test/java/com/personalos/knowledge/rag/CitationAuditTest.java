package com.personalos.knowledge.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.dto.CitationDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CitationAuditTest {
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
