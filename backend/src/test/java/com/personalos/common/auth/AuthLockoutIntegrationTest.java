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
 * 登录失败锁定的端到端行为。
 *
 * <p><b>为什么单独一个测试类</b>：失败计数是 {@code AuthService} 的单例状态，
 * 锁定一旦触发对**整个进程**生效。放进 {@code AuthIntegrationTest} 会按执行顺序
 * 随机污染其他用例（那些用例都假设自己能正常登录），症状是「单独跑绿、全量红」。
 * 这里用独立的 Spring 上下文 + 极端参数（3 次 / 1 秒）把影响关在砂箱里。
 *
 * <p><b>为什么值得测</b>：锁定是登录唯一的防暴力破解手段，
 * 而它有两种失败方式都很糟 —— 不生效（6 位口令几小时必被穷举）
 * 或解除不了（攻击者故意打错就能把你永久关在门外）。
 * 后者尤其需要验证：{@code lockedUntil} 是内存里的时间戳，
 * 「到期自动解锁」只靠读代码是看不出来的。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "app.calendar.scan-enabled=false",
        "app.auth.enabled=true",
        "app.auth.username=admin",
        "app.auth.password=test-secret",
        "app.auth.max-failures=3",
        "app.auth.lockout=1s"
})
@Testcontainers
class AuthLockoutIntegrationTest {

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

    AuthLockoutIntegrationTest(@Value("${local.server.port}") int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("连续失败达阈值后锁定：此时即使密码正确也拿 429，而不是放行")
    void locksAfterConsecutiveFailures() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(login("admin", "wrong-" + attempt).statusCode())
                    .as("第 %d 次错密码应是 401", attempt + 1)
                    .isEqualTo(401);
        }

        HttpResponse<String> locked = login("admin", PASSWORD);

        assertThat(locked.statusCode()).as("锁定期间必须拒绝，哪怕密码是对的").isEqualTo(429);
        JsonNode body = mapper.readTree(locked.body());
        assertThat(body.path("code").asInt()).isEqualTo(42900);
        // 文案里要有剩余秒数：只说「太频繁」会让用户反复重试并不断续期
        assertThat(body.path("message").asString()).contains("秒");
    }

    @Test
    @DisplayName("锁定期满自动解除：不需要重启，也不会永久把人关在门外")
    void unlocksAfterLockoutExpires() throws Exception {
        // 清场：失败计数是 AuthService 的单例状态，同一个类里的用例共享它。
        // JUnit 方法顺序不定，上一个用例可能正处在锁定中 —— 不等它过期，
        // 下面的「3 次失败」就会打在锁定窗口里（返回 429 而不计数），断言随之漂移。
        Thread.sleep(1_300);

        for (int attempt = 0; attempt < 3; attempt++) {
            login("admin", "wrong-" + attempt);
        }
        assertThat(login("admin", PASSWORD).statusCode()).isEqualTo(429);

        // lockout 配的 1 秒。留 300ms 余量避免边界抖动
        Thread.sleep(1_300);

        HttpResponse<String> afterLockout = login("admin", PASSWORD);

        assertThat(afterLockout.statusCode()).as("到期后必须能正常登录").isEqualTo(200);
        assertThat(mapper.readTree(afterLockout.body()).path("data").path("username").asString())
                .isEqualTo("admin");
    }
}
