package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 单模型查窗口的**端到端**行为（阶段 24）：真的发一次 HTTP，打到本地桩上。
 *
 * <h2>为什么不用 mock HttpClient</h2>
 * mock 只能证明「我调用了某个方法」，证明不了「请求真的打到了
 * {@code /v1/models/名字}、响应真的被解析」。而这一层最容易出的错恰恰是
 * 路径与解析：模型名带斜杠（{@code kimi/k3.1-pro}）时整段 URL 编码会变成
 * {@code %2F}，多数网关直接 404 —— 这类错在 mock 里永远看不出来。
 *
 * <p>用 JDK 自带的 {@link HttpServer}（零依赖）。
 */
@DisplayName("单模型查窗口的真实请求行为")
class ModelListProbeServiceWindowTest {

    private HttpServer server;
    private String baseUrl;
    private ModelListProbeService service;

    /** 桩收到的路径（断言「打到了哪个 URL」） */
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new ModelListProbeService(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 按模型名决定响应：派生值 / 真实值 / 429 / 404 / 无窗口字段 */
    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        receivedPaths.add(path);
        String name = path.substring(path.lastIndexOf("/v1/models/") + "/v1/models/".length());

        if (name.contains("limited")) {
            respond(exchange, 429, "{\"error\":\"rate limited\"}");
            return;
        }
        if (name.contains("unknown")) {
            respond(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }
        if (name.contains("nowindow")) {
            respond(exchange, 200, "{\"id\":\"" + name + "\",\"object\":\"model\"}");
            return;
        }
        if (name.contains("realwindow")) {
            // 与 max_tokens 不满足 -2048 关系 → 视为真实的独立数值
            respond(exchange, 200, """
                    {"id":"%s","extra_info":{"default_envs":{"max_tokens":131072,"max_input_tokens":200000}}}
                    """.formatted(name));
            return;
        }
        if (name.contains("toplevel")) {
            respond(exchange, 200, "{\"id\":\"" + name + "\",\"context_length\":32768}");
            return;
        }
        // DashScope 的真实形状：三件套，且 max_input_tokens = max_tokens - 2048
        respond(exchange, 200, """
                {"id":"%s","extra_info":{"default_envs":{
                  "max_tokens":131072,"max_input_tokens":129024,"max_output_tokens":8192}}}
                """.formatted(name));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    @DisplayName("派生值被忽略：max_input_tokens = max_tokens - 2048 时不当能力（否则每个模型都是 126K）")
    void shouldIgnoreDerivedWindow() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "deepseek-v4.1-flash");

        assertThat(result.ok())
                .as("DashScope 对几乎所有模型都回 129024，连文档 1M 的 qwen-long 也是 —— 这是派生值")
                .isFalse();
        assertThat(result.window()).isNull();
        assertThat(result.kind()).isEqualTo("UNSUPPORTED");
        assertThat(receivedPaths).containsExactly("/v1/models/deepseek-v4.1-flash");
    }

    @Test
    @DisplayName("与 max_tokens 不构成派生关系时，照常采信端点的独立数值")
    void shouldAcceptIndependentWindow() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "realwindow-model");

        assertThat(result.ok()).isTrue();
        assertThat(result.window()).isEqualTo(200_000);
    }

    @Test
    @DisplayName("顶层 context_length 优先于嵌套容器")
    void shouldReadTopLevelField() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "toplevel-model");

        assertThat(result.ok()).isTrue();
        assertThat(result.window()).isEqualTo(32_768);
    }

    @Test
    @DisplayName("端点地址已含 /v1 时不会拼成 /v1/v1/models")
    void shouldNotDuplicateV1() {
        service.probeWindow(ModelProtocol.OPENAI, baseUrl + "/v1", "sk-test", "qwen-max");

        assertThat(receivedPaths).containsExactly("/v1/models/qwen-max");
    }

    @Test
    @DisplayName("模型名带斜杠时保留斜杠，只编码各段（整段编码会 404）")
    void shouldKeepSlashInModelName() {
        service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "kimi/k3.1-pro");

        assertThat(receivedPaths).singleElement().asString().isEqualTo("/v1/models/kimi/k3.1-pro");
    }

    @Test
    @DisplayName("429 归为 RATE_LIMITED（可重试），而不是「端点不支持」")
    void shouldReportRateLimit() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "limited-model");

        assertThat(result.ok()).isFalse();
        assertThat(result.kind()).isEqualTo("RATE_LIMITED");
        assertThat(result.error()).contains("限流");
    }

    @Test
    @DisplayName("404 归为 UNSUPPORTED：这个端点没有详情接口，但仍能正常聊天")
    void shouldReportUnsupportedOn404() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "unknown-model");

        assertThat(result.ok()).isFalse();
        assertThat(result.kind()).isEqualTo("UNSUPPORTED");
        assertThat(result.error()).contains("手动填写");
    }

    @Test
    @DisplayName("200 但没有窗口字段 → UNSUPPORTED，而不是 ok + null")
    void shouldReportUnsupportedWhenFieldMissing() {
        ModelListProbeService.WindowProbeResult result =
                service.probeWindow(ModelProtocol.OPENAI, baseUrl, "sk-test", "nowindow-model");

        assertThat(result.ok()).isFalse();
        assertThat(result.kind()).isEqualTo("UNSUPPORTED");
    }

    @Test
    @DisplayName("空模型名与空端点都是参数错误（不是「查不到」）")
    void shouldRejectBlankInput() {
        assertThatThrownBy(() -> service.probeWindow(ModelProtocol.OPENAI, baseUrl, "k", "  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型名不能为空");
        assertThatThrownBy(() -> service.probeWindow(ModelProtocol.OPENAI, "  ", "k", "m"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("端点地址");
    }

    @Test
    @DisplayName("detailBase：有无 /v1、有无尾斜杠都收敛到同一基准")
    void detailBaseShouldNormalize() {
        assertThat(ModelListProbeService.detailBase("https://api.example.com")).isEqualTo("https://api.example.com");
        assertThat(ModelListProbeService.detailBase("https://api.example.com/")).isEqualTo("https://api.example.com");
        assertThat(ModelListProbeService.detailBase("https://api.example.com/v1")).isEqualTo("https://api.example.com");
        assertThat(ModelListProbeService.detailBase("https://api.example.com/v1/")).isEqualTo("https://api.example.com");
    }
}
