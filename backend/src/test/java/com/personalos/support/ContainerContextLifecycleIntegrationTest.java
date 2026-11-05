package com.personalos.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=none"})
@Testcontainers
class ContainerContextLifecycleIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final TrackingPostgres POSTGRES = new TrackingPostgres();

    @Autowired ConfigurableApplicationContext context;
    @Autowired HikariDataSource dataSource;

    static class TrackingPostgres extends PostgreSQLContainer {
        ConfigurableApplicationContext context;
        HikariDataSource dataSource;

        TrackingPostgres() { super("pgvector/pgvector:pg17"); }

        @Override public void stop() {
            try {
                if (context != null) {
                    assertThat(context.isActive()).as("Spring must close before PostgreSQL stops").isFalse();
                    assertThat(dataSource.isClosed()).as("pool must close while PostgreSQL is alive").isTrue();
                }
            } finally {
                super.stop();
            }
        }
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class Config {
        @org.springframework.context.annotation.Bean
        org.springframework.ai.embedding.EmbeddingModel embeddings() {
            return new DeterministicEmbeddingModel(1024);
        }
    }

    @Test void contextAndPoolRemainUsableUntilTheClassCompletes() throws Exception {
        POSTGRES.context = context;
        POSTGRES.dataSource = dataSource;
        assertThat(context.isActive()).isTrue();
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection();
                var result = connection.createStatement().executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
    }
}
