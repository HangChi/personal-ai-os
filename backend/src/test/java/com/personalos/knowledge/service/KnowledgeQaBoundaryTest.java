package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class KnowledgeQaBoundaryTest {
    @Test void validatesQuestionsAndNormalizesNullableAnswersAndCitationIndexes() {
        var search = mock(KnowledgeSearchPort.class);
        var provider = mock(AiChatClientProvider.class);
        var client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(provider.requirePlainChatClient()).thenReturn(client);
        var service = new KnowledgeQaService(search, provider);
        for (String q : Arrays.asList(null, " "))
            assertThatThrownBy(() -> service.ask(q, 2)).isInstanceOf(RuntimeException.class);
        var hits = List.of(new KnowledgeHitDto(UUID.randomUUID(), "Title", "", 0, "Evidence", .9));
        when(search.retrieve("question", 2)).thenReturn(RagResult.legacy(hits, "question"));
        var audit = mock(CitationAudit.class);
        service.configureCitationAudit(audit, false);
        for (String answer : Arrays.asList(null, "[0][2][99999999999999999999]", "[1][1]")) {
            when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn(answer);
            var result = service.ask(" question ", 2);
            assertThat(result.answer()).isEqualTo(answer == null ? "" : answer);
            assertThat(result.citations()).hasSize(1);
        }
        verifyNoInteractions(audit);
        service.configureCitationAudit(audit, true);
        service.ask("question", 2);
        verify(audit).check(eq("[1][1]"), anyList());
    }
}
