package com.personalos.common.maintenance;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import com.personalos.chat.repository.TokenUsageRepository;
import com.personalos.common.idempotency.OperationRecordRepository;
import java.time.*;
import org.junit.jupiter.api.Test;

class DataRetentionServiceTest {
    private final TokenUsageRepository usage = mock(TokenUsageRepository.class);
    private final OperationRecordRepository operations = mock(OperationRecordRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC);

    @Test void permanentRetentionNeverDeletes() {
        new DataRetentionService(usage, operations, clock, 0, 0).cleanup();
        verifyNoInteractions(usage, operations);
    }
    @Test void enabledPoliciesUseIndependentCutoffs() {
        new DataRetentionService(usage, operations, clock, 180, 30).cleanup();
        verify(usage).deleteByCreatedAtBefore(clock.instant().minusSeconds(180L * 86400));
        verify(operations).deleteByCreatedAtBefore(clock.instant().minusSeconds(30L * 86400));
    }
    @Test void eachPolicyCanBeEnabledIndependently() {
        new DataRetentionService(usage, operations, clock, 0, 30).cleanup();
        verifyNoInteractions(usage);
        new DataRetentionService(usage, operations, clock, 30, 0).cleanup();
        verify(usage).deleteByCreatedAtBefore(clock.instant().minusSeconds(30L * 86400));
    }
    @Test void rejectsNegativeDays() {
        assertThatThrownBy(() -> new DataRetentionService(usage, operations, clock, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DataRetentionService(usage, operations, clock, 0, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
