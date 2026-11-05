package com.personalos.common.ai;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=none"})
@Testcontainers
class ContextBudgetPersistenceIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class Stubs {
        @Bean EmbeddingModel embeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }

    @Autowired ContextBudgetService runningService;
    @Autowired ContextBudgetPreferenceRepository preferences;
    @Autowired JdbcTemplate jdbc;

    @Test
    void selectionSurvivesServiceRecreationAndSmallModelVisits() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("test");
        assertThat(preferences.findBudgetTokens()).isEmpty();
        assertThat(runningService.budgetTokens()).isEqualTo(32_768);
        runningService.setBudgetTokens(979_008);

        var properties = new AiProperties(new AiProperties.Memory(20, 32_768), null);
        var restarted = new ContextBudgetService(properties, preferences);
        restarted.registerWindowLookup(name -> "small".equals(name) ? 32_768 : 983_616);
        assertThat(restarted.budgetTokensFor("small")).isLessThan(32_768);
        assertThat(restarted.budgetTokensFor("large")).isEqualTo(979_008);
        assertThat(preferences.findBudgetTokens()).hasValue(979_008);

        restarted.setBudgetTokens(524_288);
        assertThat(new ContextBudgetService(properties, preferences).budgetTokens()).isEqualTo(524_288);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chat_context_preference", Integer.class)).isEqualTo(1);
    }
}
