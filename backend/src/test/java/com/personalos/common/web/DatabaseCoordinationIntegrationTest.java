package com.personalos.common.web;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class DatabaseCoordinationIntegrationTest {
    @Container static final PostgreSQLContainer DB = new PostgreSQLContainer("pgvector/pgvector:pg17");
    JdbcTemplate jdbc;
    TransactionTemplate transaction;
    DatabaseCoordination first;
    DatabaseCoordination second;
    @BeforeEach void setup() throws Exception {
        var source=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());
        jdbc=new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS runtime_lease,runtime_rate_bucket");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V37__runtime_coordination.sql")));
        var manager=new DataSourceTransactionManager(source);
        transaction=new TransactionTemplate(manager);
        first=new DatabaseCoordination(jdbc,manager);
        second=new DatabaseCoordination(jdbc,manager);
    }
    @AfterEach void close() { first.close(); second.close(); }
    @Test void independentProcessesShareCapacityAndRelease() {
        var held=first.acquire("model",1);
        assertThat(held).isNotNull();
        assertThat(second.acquire("model",1)).isNull();
        held.close();
        assertThat(second.acquire("model",1)).isNotNull();
    }
    @Test void expiredOwnerCannotCommitOrReleaseNewOwner() {
        var old=first.acquire("document",1);
        jdbc.update("UPDATE runtime_lease SET lease_until=now()-interval '1 second'");
        var replacement=second.acquire("document",1);
        assertThat(replacement).isNotNull();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> old.fence())).isInstanceOf(IllegalStateException.class);
        old.close();
        transaction.executeWithoutResult(status -> replacement.fence());
        assertThat(first.acquire("document",1)).isNull();
    }
    @Test void renewalKeepsValidOwnershipAndNotExpiredOwnership() {
        var held=first.acquire("document",1);
        first.renew();
        transaction.executeWithoutResult(status -> held.fence());
        jdbc.update("UPDATE runtime_lease SET lease_until=now()-interval '1 second'");
        first.renew();
        assertThat(held.valid()).isFalse();
    }
    @Test void releaseIsDeferredUntilBusinessTransactionCompletes() {
        var held=first.acquire("document",1);
        transaction.executeWithoutResult(status -> {
            held.fence(); held.close();
            assertThat(jdbc.queryForObject("SELECT owner IS NOT NULL FROM runtime_lease",Boolean.class)).isTrue();
        });
        assertThat(second.acquire("document",1)).isNotNull();
    }
    @Test void rateBudgetIsSharedAndRefillsUsingDatabaseClock() {
        assertThat(first.consume("model",1,Duration.ofMinutes(1))).isTrue();
        assertThat(second.consume("model",1,Duration.ofMinutes(1))).isFalse();
        jdbc.update("UPDATE runtime_rate_bucket SET refilled_at=now()-interval '2 minutes'");
        assertThat(second.consume("model",1,Duration.ofMinutes(1))).isTrue();
    }
    @Test void fencingOutsideTransactionIsRejected() {
        var held=first.acquire("document",1);
        assertThatThrownBy(held::fence).isInstanceOf(IllegalStateException.class);
    }
    @Test void backgroundProcessesCannotConsumeTheApiReservedSlot() {
        var worker=new ModelConcurrencyGate(2);
        worker.coordinate(first,new com.personalos.common.config.RuntimeRole("index"));
        var api=new ModelConcurrencyGate(2);
        api.coordinate(second,new com.personalos.common.config.RuntimeRole("api"));
        try (var background=worker.tryAcquire(); var foreground=api.tryAcquire()) {
            assertThat(background).isNotNull(); assertThat(foreground).isNotNull();
            assertThat(worker.tryAcquire()).isNull();
        }
    }
}
