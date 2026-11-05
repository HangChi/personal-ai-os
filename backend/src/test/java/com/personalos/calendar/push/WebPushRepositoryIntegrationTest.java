package com.personalos.calendar.push;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.personalos.common.json.JsonCodec;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "app.model-key-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Testcontainers
class WebPushRepositoryIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @TestConfiguration static class Stubs {
        @Bean EmbeddingModel embeddingModel() { return new com.personalos.support.DeterministicEmbeddingModel(1024); }
    }
    @Autowired WebPushRepository repository;
    @Autowired JdbcTemplate jdbc;
    final Instant now = Instant.parse("2026-11-01T00:00:00Z");
    final String endpoint = "https://fcm.googleapis.com/test";

    @BeforeEach void clean() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("test");
        jdbc.execute("TRUNCATE web_push_subscription, reminder CASCADE");
    }
    UUID reminder(Instant time, String state) {
        return jdbc.queryForObject("INSERT INTO reminder(source_type, source_id, title, occurrence_at, remind_at, status) "
                + "VALUES ('TASK', ?, 'meeting', ?, ?, ?) RETURNING id", UUID.class,
                UUID.randomUUID(), time.atOffset(ZoneOffset.UTC), time.atOffset(ZoneOffset.UTC), state);
    }
    @Test void queuesOncePerBrowserAndSkipsOldReadAndFutureReminders() {
        var sub = repository.subscribe(endpoint, "key", "auth", now.minusSeconds(120));
        assertThat(repository.subscribe(endpoint, "new-key", "auth", now)).isEqualTo(sub);
        assertThat(repository.exists(endpoint)).isTrue();
        assertThat(repository.count()).isEqualTo(1);
        UUID due = reminder(now.minusSeconds(30), "PENDING");
        reminder(now.minusSeconds(180), "PENDING");
        reminder(now.minusSeconds(10), "READ");
        reminder(now.plusSeconds(60), "PENDING");
        repository.enqueue(now);
        repository.enqueue(now);
        var claimed = repository.claim(now);
        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().reminderId()).isEqualTo(due);
        assertThat(claimed.getFirst().publicKey()).isEqualTo("new-key");
        assertThat(repository.claim(now)).isEmpty();
        repository.finish(claimed.getFirst(), "SENT", now);
        repository.enqueue(now);
        assertThat(repository.claim(now.plusSeconds(200))).isEmpty();
        repository.unsubscribe(endpoint);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM web_push_delivery", Integer.class)).isZero();
    }
    @Test void leasesRecoverAcrossRestartAndFinishedAttemptsCannotOverwriteNewerClaims() {
        repository.subscribe(endpoint, "key", "auth", now.minusSeconds(120));
        reminder(now.minusSeconds(30), "PENDING");
        repository.enqueue(now);
        var first = repository.claim(now).getFirst();
        var second = repository.claim(now.plusSeconds(181)).getFirst();
        assertThat(second.attempts()).isEqualTo(2);
        repository.finish(first, "SENT", now);
        assertThat(jdbc.queryForObject("SELECT state FROM web_push_delivery", String.class)).isEqualTo("SENDING");
        repository.finish(second, "PENDING", now.plusSeconds(240));
        assertThat(repository.claim(now.plusSeconds(239))).isEmpty();
        assertThat(repository.claim(now.plusSeconds(240))).hasSize(1);
    }
    @Test void transportRunsOutsideDatabaseTransactionAndExpiredSubscriptionIsRemoved() throws Exception {
        repository.subscribe(endpoint, "key", "auth", now.minusSeconds(120));
        reminder(now.minusSeconds(30), "PENDING");
        PushTransport transport = mock(PushTransport.class);
        when(transport.send(anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return 410;
        });
        new WebPushService(new WebPushSettings(true, "public", "private", "mailto:test@example.com"),
                repository, transport, new JsonCodec(new ObjectMapper()), Clock.fixed(now, ZoneOffset.UTC)).dispatch();
        assertThat(repository.count()).isZero();
    }

    @Autowired com.personalos.common.ai.config.AiModelConfigRepository modelConfig;
    @Autowired com.personalos.common.ai.config.AiModelProfileRepository modelProfiles;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @Test void optionalEncryptionWorksThroughHibernateAndKeepsRuntimeKeysReadable() {
        var config = new com.personalos.common.ai.config.AiModelConfigEntity(now);
        config.overwrite(null, "integration-fake-key", "model", null, null, now);
        modelConfig.saveAndFlush(config);
        assertThat(jdbc.queryForObject("SELECT api_key FROM ai_model_config WHERE id = 1", String.class))
                .startsWith("{aesgcm:v1}").doesNotContain("integration-fake-key");
        assertThat(modelConfig.findSingleton().orElseThrow().getApiKey()).isEqualTo("integration-fake-key");
        var profile = new com.personalos.common.ai.config.AiModelProfileEntity("test", null, 0, now);
        profile.overwrite("test", null, null, "profile-fake-key", "model", null, null, now);
        var saved = modelProfiles.saveAndFlush(profile);
        assertThat(jdbc.queryForObject("SELECT api_key FROM ai_model_profile WHERE id = ?", String.class, saved.getId()))
                .startsWith("{aesgcm:v1}").doesNotContain("profile-fake-key");
        assertThat(modelProfiles.findById(saved.getId()).orElseThrow().getApiKey()).isEqualTo("profile-fake-key");
        jdbc.update("UPDATE ai_model_config SET api_key = 'legacy-fake-key' WHERE id = 1");
        assertThat(modelConfig.findSingleton().orElseThrow().getApiKey()).isEqualTo("legacy-fake-key");
    }

    @Test void pushEndpointsExposeOnlyPublicConfigurationAndValidateRequests() throws Exception {
        var http = new com.personalos.support.HttpTestClient(port);
        var response = http.get("/api/v1/reminders/push");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"enabled\":false", "\"publicKey\":null").doesNotContain("privateKey");
        assertThat(http.post("/api/v1/reminders/push/subscriptions", "{\"endpoint\":\"https://localhost/\",\"keys\":null}").status()).isEqualTo(400);
        assertThat(http.post("/api/v1/reminders/push/subscriptions", "{\"endpoint\":\"https://fcm.googleapis.com/test\",\"keys\":{\"p256dh\":\"key\",\"auth\":\"auth\"}}").status()).isEqualTo(409);
        var deleteRequest = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/v1/reminders/push/subscriptions"))
                .header("Content-Type", "application/json").method("DELETE", java.net.http.HttpRequest.BodyPublishers.ofString("{\"endpoint\":\"https://fcm.googleapis.com/test\"}")).build();
        assertThat(java.net.http.HttpClient.newHttpClient().send(deleteRequest, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }
}
