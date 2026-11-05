package com.personalos.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.web.DatabaseCoordination;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Actual HTTP, annotated builtin callbacks and PostgreSQL; model calls must stay at zero. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
        "app.auth.enabled=false", "app.security.csrf-enabled=false",
        "app.context.summary.enabled=false", "app.context.auto-memory.enabled=false",
        "app.memory.backfill.enabled=false"
})
@Testcontainers
class DirectToolIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConfirmationService confirmations;
    @Autowired DatabaseCoordination coordination;
    @Autowired NeverCalledModel model;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    static class NeverCalledModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        @Override public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            throw new AssertionError("Direct commands must not invoke a chat model");
        }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            calls.incrementAndGet();
            return Flux.error(new AssertionError("Direct commands must not invoke a chat model"));
        }
        @Override public ChatOptions getOptions() { return ChatOptions.builder().build(); }
    }
    @TestConfiguration static class Config {
        @Bean NeverCalledModel model() { return new NeverCalledModel(); }
        @Bean EmbeddingModel embeddings() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }
    @AfterEach void modelWasNotCalled() { assertThat(model.calls).hasValue(0); }

    @Test void taskCreationReplaysAcrossRequestsAndRejectsChangedPayload() throws Exception {
        var catalog = request("GET", "/chat/tools", null, null);
        assertThat(catalog.statusCode()).isEqualTo(200);
        assertThat(data(catalog).toString()).contains("createTask", "inputSchema").doesNotContain("askUser");
        String conversation = session();
        String key = UUID.randomUUID().toString();
        String title = "direct-" + key;
        var first = command(conversation, key, "createTask", Map.of("title", title));
        assertThat(first.statusCode()).isEqualTo(200);
        String task = data(first).path("result").path("data").path("id").asString();
        assertThat(data(first).path("result").path("status").asString()).isEqualTo("OK");
        assertThat(data(first).path("replayed").asBoolean()).isFalse();
        var replay = command(conversation, key.toUpperCase(), "createTask", Map.of("title", title));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        assertThat(data(replay).path("result")).isEqualTo(data(first).path("result"));
        assertThat(taskCount(title)).isEqualTo(1);
        assertThat(command(conversation, key, "createTask", Map.of("title", "different")).statusCode()).isEqualTo(409);
        assertThat(command(session(), key, "createTask", Map.of("title", title)).statusCode()).isEqualTo(409);
        assertThat(taskCount(title)).isEqualTo(1);
        assertThat(command(conversation, UUID.randomUUID().toString(), "createTask", Map.of("title", title)).statusCode()).isEqualTo(200);
        assertThat(taskCount(title)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task WHERE id = ?", Integer.class, UUID.fromString(task))).isEqualTo(1);
        assertThat(request("POST", "/chat/sessions/" + conversation + "/tools",
                Map.of("tool", "createTask", "arguments", Map.of("title", title)), null).statusCode()).isEqualTo(400);
        assertThat(command(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "createTask", Map.of("title", title)).statusCode()).isEqualTo(404);
        assertThat(taskCount(title)).isEqualTo(2);
    }

    @Test void deleteKeepsOriginalConfirmationAndCannotExecuteBeforeApprovalOrRecreateOnReplay() throws Exception {
        String conversation = session();
        String createKey = UUID.randomUUID().toString();
        String title = "delete-" + createKey;
        var created = command(conversation, createKey, "createTask", Map.of("title", title));
        String task = data(created).path("result").path("data").path("id").asString();
        String deleteKey = UUID.randomUUID().toString();
        var args = Map.of("taskIds", java.util.List.of(task));
        var first = command(conversation, deleteKey, "deleteTask", args);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(data(first).path("result").path("status").asString()).isEqualTo("REQUIRES_CONFIRMATION");
        String confirmation = data(first).path("result").path("confirmationId").asString();
        var replay = command(conversation, deleteKey, "deleteTask", args);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(data(replay).path("result").path("confirmationId").asString()).isEqualTo(confirmation);
        assertThat(taskCount(title)).isEqualTo(1);
        var pending = confirmations.list(conversation, ConfirmationStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().id().toString()).isEqualTo(confirmation);
        var decision = Map.of("token", pending.getFirst().token());
        var approved = request("POST", "/agent/confirmations/" + confirmation + "/confirm", decision, null);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(data(approved).path("executed").asBoolean()).isTrue();
        assertThat(taskCount(title)).isZero();
        assertThat(request("POST", "/agent/confirmations/" + confirmation + "/confirm", decision, null).statusCode()).isEqualTo(409);
        assertThat(command(conversation, deleteKey, "deleteTask", args).statusCode()).isEqualTo(200);
        assertThat(command(conversation, createKey, "createTask", Map.of("title", title)).statusCode()).isEqualTo(200);
        assertThat(taskCount(title)).isZero();
        assertThat(confirmations.list(conversation, ConfirmationStatus.PENDING)).isEmpty();
    }

    @Test void occupiedSessionRejectsCommandWithoutTakingOwnership() throws Exception {
        String conversation = session(), key = UUID.randomUUID().toString(), title = "busy-" + key;
        try (var lease = coordination.acquire("chat-session:" + conversation, 1)) {
            assertThat(lease).isNotNull();
            assertThat(command(conversation, key, "createTask", Map.of("title", title)).statusCode()).isEqualTo(409);
            assertThat(taskCount(title)).isZero();
        }
        assertThat(command(conversation, key, "createTask", Map.of("title", title)).statusCode()).isEqualTo(200);
        assertThat(taskCount(title)).isEqualTo(1);
    }

    private int taskCount(String title) {
        return jdbc.queryForObject("SELECT count(*) FROM task WHERE title = ?", Integer.class, title);
    }
    private String session() throws Exception {
        var response = request("POST", "/chat/sessions", Map.of("title", "Direct command fixture"), null);
        assertThat(response.statusCode()).isEqualTo(201);
        return data(response).path("conversationId").asString();
    }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).path("data"); }
    private HttpResponse<String> command(String conversation, String key, String tool, Object args) throws Exception {
        return request("POST", "/chat/sessions/" + conversation + "/tools", Map.of("tool", tool, "arguments", args), key);
    }
    private HttpResponse<String> request(String method, String path, Object body, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
}
