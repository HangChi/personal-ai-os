package com.personalos.chat.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.personalos.chat.entity.ChatSessionEntity;
import com.personalos.chat.service.ChatSessionService;
import com.personalos.chat.service.UsageStatsService;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.ContextBudgetService;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class ChatContextControllerTest {
    @Test
    void budgetResponseKeepsSelectedModelsWindowAndActualUsage() {
        var props = new AiProperties(new AiProperties.Memory(20, 131_072), null);
        var budget = new ContextBudgetService(props);
        budget.registerDefaultModel(() -> "default-small");
        budget.registerWindowLookup(name -> "default-small".equals(name) ? 8_192 : 2_000_000);
        var sessions = mock(ChatSessionService.class);
        var session = mock(ChatSessionEntity.class);
        when(sessions.requireSession("current")).thenReturn(session);
        when(session.getChatModel()).thenReturn("large-custom");
        var usage = mock(UsageStatsService.class);
        when(usage.latestActualPromptTokens("current")).thenReturn(OptionalInt.of(4_096));
        var controller = new ChatSessionController(sessions, null, null, props, budget, null, usage);
        var changed = controller.setContextBudget(new ChatSessionController.ContextBudgetRequest(2_000_000, "current")).data();
        assertThat(changed.modelName()).isEqualTo("large-custom");
        assertThat(changed.contextWindow()).isEqualTo(2_000_000);
        assertThat(changed.budgetTokens()).isEqualTo(2_000_000);
        assertThat(changed.promptTokens()).isEqualTo(4_096);
        assertThat(changed.estimated()).isFalse();
        var blank = controller.defaultContextUsage().data();
        assertThat(blank.modelName()).isEqualTo("default-small");
        assertThat(blank.contextWindow()).isEqualTo(8_192);
        assertThat(blank.budgetTokens()).isEqualTo(8_192);
    }
}
