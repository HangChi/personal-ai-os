package com.personalos.common.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import com.personalos.common.idempotency.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"spring.ai.model.chat=none"})
@Testcontainers
class OperationRecordRetentionIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @Autowired OperationRecordRepository repository;

    @Test
    void retentionPreservesUncertainToolOwnershipButDeletesOldCompletedReceipts() {
        repository.deleteAll();
        var cutoff = Instant.parse("2026-10-02T00:00:00Z");
        insert("tool-unknown", OperationSource.TOOL, IdempotencyOutcome.IN_PROGRESS, cutoff.minusSeconds(1));
        insert("tool-failed", OperationSource.TOOL, IdempotencyOutcome.FAILED, cutoff.minusSeconds(1));
        insert("tool-success", OperationSource.TOOL, IdempotencyOutcome.SUCCESS, cutoff.minusSeconds(1));
        insert("http", OperationSource.HTTP, IdempotencyOutcome.IN_PROGRESS, cutoff.minusSeconds(1));
        insert("boundary", OperationSource.TOOL, IdempotencyOutcome.SUCCESS, cutoff);
        assertThat(repository.deleteByCreatedAtBefore(cutoff)).isEqualTo(2);
        assertThat(repository.findAll()).extracting(OperationRecordEntity::getIdempotencyKey)
                .containsExactlyInAnyOrder("tool-unknown", "tool-failed", "boundary");
        assertThat(repository.deleteByCreatedAtBefore(cutoff)).isZero();
    }

    private void insert(String key, OperationSource source, IdempotencyOutcome outcome, Instant created) {
        var row = new OperationRecordEntity(key, source, "fixture", "digest", null, null, created);
        if (outcome != IdempotencyOutcome.IN_PROGRESS) row.complete(outcome, "{}", 1);
        repository.saveAndFlush(row);
    }
}
