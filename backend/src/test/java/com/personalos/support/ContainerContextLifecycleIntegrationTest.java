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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "spring.ai.model.embedding=none"})
@Testcontainers
class ContainerContextLifecycleIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final TrackingPostgres POSTGRES = new TrackingPostgres();

    @Autowired ConfigurableApplicationContext context;
    @Autowired HikariDataSource dataSource;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    static class TrackingPostgres extends PostgreSQLContainer {
        ConfigurableApplicationContext context;
        HikariDataSource dataSource;
        java.net.http.HttpClient client;
        java.io.InputStream liveStream;
        long shutdownStarted;

        TrackingPostgres() { super("pgvector/pgvector:pg17"); }

        @Override public void stop() {
            try {
                if (context != null) {
                    assertThat(context.isActive()).as("Spring must close before PostgreSQL stops").isFalse();
                    assertThat(dataSource.isClosed()).as("pool must close while PostgreSQL is alive").isTrue();
                    assertThat(System.nanoTime() - shutdownStarted)
                            .as("live reminder SSE must not consume Tomcat's 30-second shutdown budget")
                            .isLessThan(java.time.Duration.ofSeconds(5).toNanos());
                }
            } finally {
                try {
                    if (liveStream != null) liveStream.close();
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                } finally {
                    if (client != null) client.close();
                    super.stop();
                }
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
        context.addApplicationListener(event -> {
            if (event instanceof org.springframework.context.event.ContextClosedEvent closed
                    && closed.getApplicationContext() == context) {
                POSTGRES.shutdownStarted = System.nanoTime();
            }
        });
        assertThat(context.isActive()).isTrue();
        assertThat(POSTGRES.isRunning()).isTrue();
        try (var connection = dataSource.getConnection();
                var result = connection.createStatement().executeQuery("SELECT 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        POSTGRES.client = java.net.http.HttpClient.newHttpClient();
        var request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://127.0.0.1:" + port + "/api/v1/reminders/events"))
                .timeout(java.time.Duration.ofSeconds(5)).header("Accept", "text/event-stream").GET().build();
        var response = POSTGRES.client.send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        POSTGRES.liveStream = response.body();
        var reader = new java.io.BufferedReader(new java.io.InputStreamReader(POSTGRES.liveStream,
                java.nio.charset.StandardCharsets.UTF_8));
        assertThat(java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) return line;
                }
                throw new AssertionError("SSE ended before the shutdown test");
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        }).get(5, java.util.concurrent.TimeUnit.SECONDS)).contains("data:");
        // Deliberately keep the response open through Spring's real shutdown sequence.
    }
}
