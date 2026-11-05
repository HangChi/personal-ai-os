package com.personalos.common.monitoring;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.assertThat;
import com.personalos.chat.metrics.ChatStreamMetrics;
import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none", "management.server.port=0", "app.auth.enabled=true",
        "app.auth.password=monitoring-test-password"})
@ActiveProfiles("monitoring")
@Testcontainers
class MonitoringIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");
    @Value("${local.server.port}") int appPort;
    @Value("${local.management.port}") int managementPort;
    @Autowired ChatStreamMetrics metrics;

    private HttpResponse<String> get(int port, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
    }
    @Test void internalScrapeExportsMetricsButDoesNotExposeApplicationOrEnvironment() throws Exception {
        metrics.firstContent("test", "stub", 1_000_000_000L);
        var scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("personalos_chat_first_content_seconds_count", "hikaricp_connections");
        assertThat(get(appPort, "/api/v1/tasks").statusCode()).isEqualTo(401);
        assertThat(get(appPort, "/actuator/prometheus").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/api/v1/tasks").statusCode()).isNotEqualTo(200);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isNotEqualTo(200);
    }
}
