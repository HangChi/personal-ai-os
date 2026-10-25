package com.personalos.common.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.support.HttpTestClient;
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
 * 默认配置（认证关闭）下的行为。
 *
 * <p>这组用例守的是「阶段 15 没有改变默认行为」：全部接口放行、
 * {@code /auth/me} 报告 {@code authEnabled=false}、不产生无用的会话。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "app.calendar.scan-enabled=false"})
@Testcontainers
class AuthDisabledIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HttpTestClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    AuthDisabledIntegrationTest(@Value("${local.server.port}") int port) {
        this.http = new HttpTestClient(port);
    }

    @Test
    @DisplayName("未登录也能访问受保护接口（与阶段 14 行为一致）")
    void shouldAllowEverythingWhenDisabled() throws Exception {
        assertThat(http.get("/api/v1/tasks").status()).isEqualTo(200);
        assertThat(http.get("/api/v1/memories").status()).isEqualTo(200);
        assertThat(http.get("/api/v1/export").status()).isEqualTo(200);
    }

    @Test
    @DisplayName("/auth/me 报告 authEnabled=false 且无用户")
    void meShouldReportDisabled() throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/auth/me");

        assertThat(response.status()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("authEnabled").asBoolean()).isFalse();
        assertThat(data.path("username").isNull()).isTrue();
    }

    @Test
    @DisplayName("认证关闭时登录不会凭空造出一个「已登录」状态")
    void loginShouldNotFakeSessionWhenDisabled() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/auth/login",
                "{\"username\":\"admin\",\"password\":\"whatever\"}");

        assertThat(response.status()).isEqualTo(200);
        JsonNode data = mapper.readTree(response.body()).path("data");
        assertThat(data.path("authEnabled").asBoolean()).isFalse();
        assertThat(data.path("username").isNull()).isTrue();
    }
}
