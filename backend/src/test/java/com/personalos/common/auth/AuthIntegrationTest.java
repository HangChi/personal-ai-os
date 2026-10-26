package com.personalos.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 开启访问控制后的端到端行为。
 *
 * <p>用 JDK 的 {@link CookieManager} 而不是项目里的 {@code HttpTestClient}：
 * 后者不带 cookie 管理，而「会话是否真的生效」正是本阶段要验证的核心。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "app.calendar.scan-enabled=false",
        "app.auth.enabled=true",
        "app.auth.username=admin",
        "app.auth.password=test-secret"
})
@Testcontainers
class AuthIntegrationTest {

    private static final String PASSWORD = "test-secret";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final String baseUrl;
    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager())
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    AuthIntegrationTest(@Value("${local.server.port}") int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("未登录访问受保护接口：401 + 统一信封（不是 302 跳 HTML）")
    void shouldRejectAnonymous() throws Exception {
        HttpResponse<String> response = get("/api/v1/tasks");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("application/json");
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(40100);
        assertThat(body.path("timestamp").asString()).isNotBlank();
    }

    @Test
    @DisplayName("健康检查始终放行：监控探针不该需要凭据")
    void healthShouldStayPublic() throws Exception {
        assertThat(get("/api/v1/health").statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("未登录时 /auth/me 返回 200 + username=null（不是 401）")
    void meShouldReportAnonymous() throws Exception {
        HttpClient fresh = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> response = fresh.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("authEnabled").asBoolean()).isTrue();
        assertThat(data.path("username").isNull()).isTrue();
    }

    @Test
    @DisplayName("密码错误：401 + 不区分「用户名错」与「密码错」")
    void shouldRejectWrongPassword() throws Exception {
        HttpClient fresh = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        HttpResponse<String> wrongPassword = fresh.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"admin\",\"password\":\"nope\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> wrongUser = fresh.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"username\":\"root\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(wrongUser.statusCode()).isEqualTo(401);
        String first = mapper.readTree(wrongPassword.body()).path("message").asString();
        String second = mapper.readTree(wrongUser.body()).path("message").asString();
        assertThat(first).isEqualTo(second).isEqualTo("用户名或密码错误");
    }

    @Test
    @DisplayName("登录成功后可访问受保护接口；登出后再次 401")
    void loginThenLogout() throws Exception {
        assertThat(get("/api/v1/tasks").statusCode()).isEqualTo(401);

        HttpResponse<String> login = login("admin", PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        JsonNode data = mapper.readTree(login.body()).path("data");
        assertThat(data.path("username").asString()).isEqualTo("admin");
        assertThat(data.path("authEnabled").asBoolean()).isTrue();

        // 同一个 client（带 cookie）现在应能访问
        assertThat(get("/api/v1/tasks").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/auth/me").statusCode()).isEqualTo(200);

        assertThat(post("/api/v1/auth/logout", "").statusCode()).isEqualTo(200);

        // 会话已失效
        assertThat(get("/api/v1/tasks").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("登录后写操作也放行（验证保护的是整条 API，不只是查询）")
    void writeEndpointsShouldWorkAfterLogin() throws Exception {
        login("admin", PASSWORD);

        HttpResponse<String> created = post("/api/v1/tasks", "{\"title\":\"认证后的任务\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(mapper.readTree(created.body()).path("data").path("title").asString())
                .isEqualTo("认证后的任务");
    }
}
