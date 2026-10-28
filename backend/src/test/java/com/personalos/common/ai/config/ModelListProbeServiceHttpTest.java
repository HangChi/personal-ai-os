package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.config.dto.ModelListResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 模型列表探测的**端到端**行为：真的发一次 HTTP，打到本地桩上。
 *
 * <h2>为什么必须有这一层</h2>
 * {@link ModelListProbeServiceTest} 只测了 URL 字符串与请求头对象，覆盖不到
 * {@code probe()} 的主流程 —— 而这些恰恰是最容易在「单测全绿、线上不通」之间
 * 拉开差距的地方：
 * <ul>
 *   <li>路径拼对了字符串，但请求真的发出去时打的是哪个 path？</li>
 *   <li>请求头对象里塞了 {@code x-api-key}，服务端真的收到了吗？</li>
 *   <li>响应解析的三种形状（data[].id / models[].name / 裸数组）是否真的都能认？</li>
 *   <li>非 2xx、非 JSON、网络错误是否都给得出可读文案？</li>
 * </ul>
 * 用一个 JDK 自带的 {@link HttpServer}（零依赖、com.sun.net.httpserver 在
 * JDK 里就有）记录「实际收到的 path 与 headers」，再让服务去打它，才能把
 * 「我以为是这么发的」变成「确实这么发了」。
 *
 * <p>刻意**不用 {@code @Nested}**：本项目 surefire 配置下嵌套类不被执行。
 */
@DisplayName("ModelListProbeService 的真实请求行为")
class ModelListProbeServiceHttpTest {

    private HttpServer server;
    private String baseUrl;
    private ModelListProbeService service;

    /** 桩最近一次收到的请求路径 */
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    /** 桩最近一次收到的全部请求头（小写键名） */
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    /** 记录所有收到的路径，便于断言「只发了一次」或「打到了正确路径」 */
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
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

    // ------------------------------------------------------------------ 桩工具

    /**
     * 注册一个固定响应的处理器。
     *
     * @param path     期望被命中的路径；用 {@code /} 兜底所有路径
     * @param status   HTTP 状态码
     * @param body     响应体（UTF-8）
     */
    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            lastPath.set(exchange.getRequestURI().getPath());
            lastExchange.set(exchange);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private String header(String name) {
        HttpExchange exchange = lastExchange.get();
        if (exchange == null) {
            return null;
        }
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    // ------------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("OpenAI 形状：解析 data[].id，并真的打到 /v1/models")
    void openAiParsesDataIds() {
        respond("/v1/models", 200, """
                {"object":"list","data":[
                  {"id":"gpt-4o-mini","object":"model"},
                  {"id":"gpt-4o","object":"model"}
                ]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk-test");

        assertThat(result.ok()).isTrue();
        assertThat(result.error()).isNull();
        // 按小写排序：gpt-4o 排在 gpt-4o-mini 前
        assertThat(result.models()).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(lastPath.get()).isEqualTo("/v1/models");
        assertThat(header("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(header("Accept")).contains("application/json");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("Anthropic 形状：服务端真的收到 x-api-key 与 anthropic-version，且没有 Bearer")
    void anthropicSendsApiKeyHeader() {
        respond("/v1/models", 200, """
                {"data":[{"id":"claude-sonnet-4-5","type":"model"}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.ANTHROPIC, baseUrl, "sk-ant-test");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("claude-sonnet-4-5");
        assertThat(lastPath.get()).isEqualTo("/v1/models");
        assertThat(header("x-api-key")).isEqualTo("sk-ant-test");
        assertThat(header("anthropic-version")).isEqualTo("2023-06-01");
        // 端到端再确认一次：Authorization 头根本不存在（不是空串）
        assertThat(header("Authorization")).isNull();
    }

    @Test
    @DisplayName("Ollama 形状：解析 models[].name，并打到根路径的 /api/tags")
    void ollamaParsesModelNames() {
        respond("/api/tags", 200, """
                {"models":[{"name":"qwen2.5:7b","size":4500000000},
                           {"name":"llama3.2:3b","size":2000000000}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("llama3.2:3b", "qwen2.5:7b");
        assertThat(lastPath.get()).isEqualTo("/api/tags");
        // Ollama 无密钥时不该凭空造一个 Authorization 头出来
        assertThat(header("Authorization")).isNull();
    }

    @Test
    @DisplayName("Ollama：用户填了 /v1 时，实际请求的仍是 /api/tags（不出现 /v1/api/tags）")
    void ollamaStripsV1InRealRequest() {
        receivedPaths.clear();
        respond("/api/tags", 200, "{\"models\":[{\"name\":\"qwen2.5:7b\"}]}");

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl + "/v1", null);

        assertThat(result.ok()).isTrue();
        assertThat(receivedPaths).containsExactly("/api/tags");
    }

    @Test
    @DisplayName("结果是去重并排序的：重复模型名只出现一次")
    void deduplicatesModels() {
        respond("/v1/models", 200, """
                {"data":[{"id":"b-model"},{"id":"a-model"},{"id":"a-model"},{"id":"b-model"}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.models()).containsExactly("a-model", "b-model");
    }

    @Test
    @DisplayName("兜底形状：接口直接返回裸数组也能解析")
    void acceptsBareArray() {
        respond("/v1/models", 200, """
                [{"id":"solo-model"}]
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("solo-model");
    }

    @Test
    @DisplayName("兜底形状：列表藏在 models[].id 下（不是 data）也能解析")
    void acceptsModelsWithId() {
        respond("/v1/models", 200, """
                {"models":[{"id":"nested-model"}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("nested-model");
    }

    @Test
    @DisplayName("Ollama 兜底：/api/tags 吐 OpenAI 形状时也能认")
    void ollamaFallsBackToDataIds() {
        respond("/api/tags", 200, """
                {"data":[{"id":"ollama-as-openai"}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("ollama-as-openai");
    }

    // ------------------------------------------------------------------ 失败路径（都必须是 ok=false 而不是抛异常）

    @Test
    @DisplayName("404 时说清「该路径不存在」，并带出实际请求的 path")
    void notFoundIsReadable() {
        respond("/v1/models", 404, "{\"error\":\"not found\"}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isFalse();
        assertThat(result.models()).isEmpty();
        assertThat(result.error()).contains("404").contains("/v1/models").contains("可能它不提供模型列表接口");
    }

    @Test
    @DisplayName("401 时提示密钥无效或鉴权方式不对（Anthropic 带 Bearer 就是这一条）")
    void unauthorizedIsReadable() {
        respond("/v1/models", 401, "{\"error\":\"invalid api key\"}");

        ModelListResult result = service.probe(ModelProtocol.ANTHROPIC, baseUrl, "sk-bad");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("401").contains("API Key 无效");
    }

    @Test
    @DisplayName("403 / 429 各自有针对性文案，不是笼统的「请求失败」")
    void forbiddenAndRateLimitedHaveOwnMessages() {
        respond("/v1/models", 403, "{}");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").error()).contains("403").contains("无权限");

        // 换一个端口重新绑，避免同一路径重复注册
        server.removeContext("/v1/models");
        respond("/v1/models", 429, "{}");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").error()).contains("429").contains("限流");
    }

    @Test
    @DisplayName("非 JSON 响应（中转站 302 到登录页的典型症状）给出可读原因")
    void nonJsonIsReadable() {
        respond("/v1/models", 200, "<!DOCTYPE html><html><body>Please log in</body></html>");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("不是合法 JSON").contains("HTML");
    }

    @Test
    @DisplayName("JSON 里没有模型字段时说明「接口有响应但没解析到」，并提示可手填")
    void emptyListIsReadable() {
        respond("/v1/models", 200, "{\"object\":\"list\"}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("没有解析到任何模型名").contains("手动填写");
    }

    @Test
    @DisplayName("连接被拒绝时提示端口没服务在监听（Ollama 未启动的典型症状）")
    void connectionRefusedIsReadable() {
        // 先停掉桩，端口就没人监听了
        server.stop(0);
        server = null;

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl, null);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("连接被拒绝");
    }

    // ------------------------------------------------------------------ 失败分类（决定前端提示语气）

    @Test
    @DisplayName("404 归类为 UNSUPPORTED：端点没这个接口是正常情况，不该报成故障")
    void notFoundIsUnsupportedKind() {
        respond("/v1/models", 404, "{}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isFalse();
        assertThat(result.kind()).isEqualTo(ModelListResult.ProbeFailureKind.UNSUPPORTED);
    }

    @Test
    @DisplayName("405 同样归 UNSUPPORTED（有些端点只认 POST）")
    void methodNotAllowedIsUnsupportedKind() {
        respond("/v1/models", 405, "{}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.kind()).isEqualTo(ModelListResult.ProbeFailureKind.UNSUPPORTED);
    }

    @Test
    @DisplayName("返回 HTML 归 UNSUPPORTED：多半是被重定向到登录页，该端点没有可用接口")
    void htmlResponseIsUnsupportedKind() {
        respond("/v1/models", 200, "<html><body>login</body></html>");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.kind()).isEqualTo(ModelListResult.ProbeFailureKind.UNSUPPORTED);
    }

    @Test
    @DisplayName("解析不到模型名归 UNSUPPORTED：接口在，但没给列表")
    void emptyListIsUnsupportedKind() {
        respond("/v1/models", 200, "{\"object\":\"list\"}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.kind()).isEqualTo(ModelListResult.ProbeFailureKind.UNSUPPORTED);
    }

    @Test
    @DisplayName("401/403/429 与网络不通都归 ERROR：这些必须让用户去改")
    void realErrorsAreErrorKind() {
        respond("/v1/models", 401, "{}");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);

        server.removeContext("/v1/models");
        respond("/v1/models", 403, "{}");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);

        server.removeContext("/v1/models");
        respond("/v1/models", 500, "{}");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);
    }

    @Test
    @DisplayName("前置条件缺失（缺端点/缺密钥）也归 ERROR：那是用户能改的东西")
    void preconditionFailuresAreErrorKind() {
        assertThat(service.probe(ModelProtocol.OPENAI, "", "sk").kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, null).kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);
    }

    @Test
    @DisplayName("成功时 kind 为 null")
    void successHasNullKind() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"m\"}]}");

        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "sk").kind()).isNull();
    }

    @Test
    @DisplayName("4 参构造器默认按 ERROR 分类（兼容旧调用点与测试）")
    void fourArgConstructorDefaultsToError() {
        assertThat(new ModelListResult(false, List.of(), 0, "x").kind())
                .isEqualTo(ModelListResult.ProbeFailureKind.ERROR);
        // 成功结果不该被标成失败分类
        assertThat(new ModelListResult(true, List.of("m"), 5, null).kind()).isNull();
    }

    @Test
    @DisplayName("端点为空 / 缺协议 / 缺密钥都在发请求前就返回，不打网络")
    void preconditionsStopBeforeNetwork() {
        receivedPaths.clear();

        assertThat(service.probe(ModelProtocol.OPENAI, "", "sk").error()).contains("请先填写端点地址");
        assertThat(service.probe(ModelProtocol.OPENAI, "  ", "sk").error()).contains("请先填写端点地址");
        assertThat(service.probe(ModelProtocol.OPENAI, "api.openai.com", "sk").error())
                .contains("http:// 或 https://");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, null).error()).contains("请先填写 API Key");
        assertThat(service.probe(ModelProtocol.OPENAI, baseUrl, "   ").error()).contains("请先填写 API Key");

        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("protocol 传 null 时按 OpenAI 处理（不 NPE）")
    void nullProtocolFallsBackToOpenAi() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"fallback-model\"}]}");

        ModelListResult result = service.probe(null, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("fallback-model");
    }

    @Test
    @DisplayName("密钥前后空白（粘贴带换行）在真实请求里也被裁掉")
    void trimsKeyInRealRequest() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"m\"}]}");

        service.probe(ModelProtocol.OPENAI, baseUrl, "  sk-abc\n");

        assertThat(header("Authorization")).isEqualTo("Bearer sk-abc");
    }

    @Test
    @DisplayName("端点带尾斜杠与路径前缀时，实际请求路径仍正确")
    void handlesMessyBaseUrlInRealRequest() {
        receivedPaths.clear();
        respond("/compatible-mode/v1/models", 200, "{\"data\":[{\"id\":\"qwen\"}]}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl + "/compatible-mode/v1///", "sk");

        assertThat(result.ok()).isTrue();
        assertThat(receivedPaths).containsExactly("/compatible-mode/v1/models");
        assertThat(result.models()).containsExactly("qwen");
    }
}
