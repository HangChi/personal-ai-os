package com.personalos.common.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.personalos.common.json.JsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.ObjectMapper;

class ConfirmationPromptContributorTest {
    private static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    private final ConfirmationRepository repository = mock(ConfirmationRepository.class);
    private final ConfirmationPromptContributor contributor = new ConfirmationPromptContributor(
            repository, Clock.fixed(NOW, ZoneOffset.UTC), new JsonCodec(new ObjectMapper()));

    private ConfirmationEntity item(ConfirmationStatus status, Instant expiry) {
        var entity = mock(ConfirmationEntity.class);
        when(entity.getId()).thenReturn(UUID.randomUUID());
        when(entity.getStatus()).thenReturn(status);
        when(entity.getExpiresAt()).thenReturn(expiry);
        when(entity.getSummary()).thenReturn("删除旧版组会");
        when(entity.getAction()).thenReturn(ConfirmationAction.DELETE_CALENDAR_EVENT);
        when(entity.getToken()).thenReturn("must-not-leak-token");
        when(entity.getPayload()).thenReturn("must-not-leak-payload");
        return entity;
    }

    @Test
    void completedDecisionsOverrideHistoricalPendingDescriptionsWithoutLeakingSecrets() {
        var resolved = item(ConfirmationStatus.CONFIRMED, NOW.plusSeconds(60));
        when(repository.findByStatusAndConversationIdOrderByCreatedAtDesc(ConfirmationStatus.PENDING, "conversation-a"))
                .thenReturn(List.of());
        when(repository.findByConversationIdOrderByCreatedAtDesc("conversation-a", PageRequest.of(0, 12)))
                .thenReturn(List.of(resolved));
        String prompt = contributor.contribute("为什么说我没有确认", "conversation-a");
        assertThat(prompt).contains("尚待人工确认：0 项", "CONFIRMED", "删除旧版组会", "优先于历史回答", "本轮随后新产生")
                .doesNotContain("must-not-leak-token", "must-not-leak-payload");
        verify(repository, never()).findByStatusOrderByCreatedAtDesc(any());
    }

    @Test
    void expiredPendingRecordsAreNotCountedAsWaitingAndEveryTurnRefreshes() {
        var pending = item(ConfirmationStatus.PENDING, NOW.plusSeconds(60));
        var expired = item(ConfirmationStatus.PENDING, NOW);
        when(repository.findByStatusAndConversationIdOrderByCreatedAtDesc(ConfirmationStatus.PENDING, "a"))
                .thenReturn(List.of(pending, expired)).thenReturn(List.of(expired));
        when(repository.findByConversationIdOrderByCreatedAtDesc("a", PageRequest.of(0, 12)))
                .thenReturn(List.of(pending, expired));
        assertThat(contributor.contribute("检查", "a")).contains("尚待人工确认：1 项", "EXPIRED");
        assertThat(contributor.contribute("再检查", "a")).contains("尚待人工确认：0 项");
    }

    @Test
    void noConversationMeansNoCrossConversationState() {
        assertThat(contributor.contribute()).isEmpty();
        assertThat(contributor.contribute("检查", null)).isEmpty();
        assertThat(contributor.contribute("检查", " ")).isEmpty();
        verifyNoInteractions(repository);
    }
}
