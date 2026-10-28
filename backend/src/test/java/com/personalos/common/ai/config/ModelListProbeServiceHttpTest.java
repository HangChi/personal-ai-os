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
import java.util.Map;
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
        // 列表请求确实打过（之后还会有补查详情的请求，见 lookupDetailWindows）
        assertThat(receivedPaths).contains("/v1/models");
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
        assertThat(receivedPaths).contains("/v1/models");
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
        // 列表接口打对了。之后还会对 `qwen` 补查详情（桩按前缀匹配，
        // 详情路径也会命中同一个 context），所以断言「包含」而不是「恰好等于」
        assertThat(receivedPaths).contains("/compatible-mode/v1/models");
        assertThat(result.models()).containsExactly("qwen");
    }

    // ------------------------------------------------------------------ 上下文窗口（V25）

    /*
     * 为什么要在这里测窗口解析，而不是只测「字段名列表」：
     * 五种字段名各写一次单测很容易「都绿但真实响应解析不出来」——
     * 真正要保证的是「服务端返回的这段 JSON 能被翻译成映射」，
     * 而这段 JSON 的形状只能由真实响应体来代表。
     */

    @Test
    @DisplayName("OpenAI 兼容端点自报 context_length 时被带回")
    void parsesContextLengthFromOpenAiCompatible() {
        respond("/v1/models", 200, """
                {"data":[
                  {"id":"qwen-max","context_length":32768},
                  {"id":"qwen-long","context_length":1000000}
                ]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.contextWindows())
                .containsEntry("qwen-max", 32768)
                .containsEntry("qwen-long", 1000000);
    }

    @Test
    @DisplayName("vLLM 的 max_model_len 与聚合网关的 context_window 都能认")
    void parsesAlternateWindowFieldNames() {
        respond("/v1/models", 200, """
                {"data":[
                  {"id":"vllm-model","max_model_len":131072},
                  {"id":"gateway-model","context_window":65536},
                  {"id":"another","max_context_length":8192}
                ]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.contextWindows())
                .containsEntry("vllm-model", 131072)
                .containsEntry("gateway-model", 65536)
                .containsEntry("another", 8192);
    }

    @Test
    @DisplayName("Ollama 把窗口塞在 model_info 的 *.context_length 里也能认")
    void parsesNestedOllamaModelInfo() {
        respond("/api/tags", 200, """
                {"models":[
                  {"name":"llama3:8b","model_info":{"llama.context_length":8192}}
                ]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl, null);

        assertThat(result.ok()).isTrue();
        assertThat(result.contextWindows()).containsEntry("llama3:8b", 8192);
    }

    @Test
    @DisplayName("原生 OpenAI 不返回窗口字段时，映射为空而不是乱填")
    void absentWindowFieldsYieldEmptyMap() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"gpt-4o\"}]}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        // 关键：不是「有 key 但值为 0」，而是**完全不含**这个模型 ——
        // 前端据此区分「32K」与「不知道多长」
        assertThat(result.contextWindows()).isEmpty();
    }

    @Test
    @DisplayName("明显不合理的窗口值被丢弃（把字符数当 token 报的端点）")
    void rejectsImplausibleWindowValues() {
        respond("/v1/models", 200, """
                {"data":[
                  {"id":"too-small","context_length":512},
                  {"id":"too-large","context_length":100000000},
                  {"id":"zero","context_length":0},
                  {"id":"good","context_length":32768}
                ]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.contextWindows())
                .containsOnly(Map.entry("good", 32768));
    }

    // ------------------------------------------------------------------ 补查详情（V26）

    /*
     * 为什么这一组测试必须存在：
     *
     * 「拿不到模型真实窗口」是一个**静默失效**——探测返回 ok=true、模型列表
     * 完整、界面上一切正常，只有窗口那一栏是空的，用户以为功能没做。
     * 而根因藏在「列表接口不返回，详情接口才返回」这个层次差异里，
     * 只测列表解析的实现照样能全绿。所以这里用真桩把详情请求也跑一遍。
     *
     * 真实形状（2026-10-28 DashScope 实测）：
     *   GET /compatible-mode/v1/models      → 183 个模型，0 个带窗口
     *   GET /compatible-mode/v1/models/qwen-max
     *     → {"id":"qwen-max","extra_info":{"default_envs":{"max_input_tokens":30720}}}
     */

    @Test
    @DisplayName("列表不带窗口时，逐个查详情并从 extra_info 里取到窗口")
    void fallsBackToDetailEndpointForWindows() {
        // 列表：不返回任何窗口字段
        respond("/v1/models", 200, """
                {"data":[{"id":"qwen-max"},{"id":"qwen3.8-flash"}]}
                """);
        // 详情：DashScope 形状，窗口在 extra_info.default_envs.max_input_tokens
        respond("/v1/models/qwen-max", 200, """
                {"id":"qwen-max","extra_info":{"default_envs":{"max_input_tokens":30720}}}
                """);
        respond("/v1/models/qwen3.8-flash", 200, """
                {"id":"qwen3.8-flash","extra_info":{"default_envs":{"max_input_tokens":129024}}}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.contextWindows())
                .containsOnly(Map.entry("qwen-max", 30720), Map.entry("qwen3.8-flash", 129024));
        // 详情请求真的发出了，且路径正确
        assertThat(receivedPaths).contains("/v1/models/qwen-max", "/v1/models/qwen3.8-flash");
    }

    @Test
    @DisplayName("模型名含斜杠时逐段编码，斜杠本身保留不被编成 %2F")
    void encodesModelNameSegmentsButKeepsSlash() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"kimi/kimi-k3.1-pro\"}]}");
        respond("/v1/models/kimi/kimi-k3.1-pro", 200, """
                {"id":"kimi/kimi-k3.1-pro","extra_info":{"default_envs":{"max_input_tokens":131072}}}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        // 关键：路径是 /v1/models/kimi/kimi-k3.1-pro，而不是 .../kimi%2Fkimi-k3.1-pro
        assertThat(receivedPaths).contains("/v1/models/kimi/kimi-k3.1-pro");
        assertThat(result.contextWindows()).containsEntry("kimi/kimi-k3.1-pro", 131072);
    }

    @Test
    @DisplayName("列表里已经自报窗口时不再补查，省掉 N 次往返")
    void skipsDetailLookupWhenListHasWindows() {
        respond("/v1/models", 200, """
                {"data":[{"id":"a","context_length":32768},{"id":"b","context_length":131072}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.contextWindows()).containsOnly(Map.entry("a", 32768), Map.entry("b", 131072));
        // 一次额外请求都不该发：receivedPaths 里只有列表那一条
        assertThat(receivedPaths).containsExactly("/v1/models");
    }

    @Test
    @DisplayName("详情接口失败（404/500）时静默跳过，不影响已拿到的模型列表")
    void detailFailureDoesNotBreakProbe() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}");
        respond("/v1/models/m1", 404, "{\"error\":\"no such endpoint\"}");
        respond("/v1/models/m2", 500, "{\"error\":\"boom\"}");

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.models()).containsExactly("m1", "m2");
        assertThat(result.contextWindows()).isEmpty();
    }

    @Test
    @DisplayName("Ollama 不补查详情：它的窗口已在 /api/tags 的 model_info 里")
    void ollamaDoesNotProbeDetails() {
        receivedPaths.clear();
        respond("/api/tags", 200, """
                {"models":[{"name":"llama3.2:3b",
                            "model_info":{"llama.context_length":131072}}]}
                """);

        ModelListResult result = service.probe(ModelProtocol.OLLAMA, baseUrl, null);

        assertThat(result.contextWindows()).containsEntry("llama3.2:3b", 131072);
        assertThat(receivedPaths).containsExactly("/api/tags");
    }

    @Test
    @DisplayName("深度嵌套的窗口也能取到（广度优先，限深 4 层）")
    void findsDeeplyNestedWindow() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"deep\"}]}");
        respond("/v1/models/deep", 200, """
                {"id":"deep","extra_info":{"default_envs":{"max_tokens":8192,"max_input_tokens":65536}}}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        // 取 max_input_tokens 而不是 max_tokens：前者是「能塞多少输入」，
        // 后者是「最多输出多少」，用错会让预算差一个数量级
        assertThat(result.contextWindows()).containsEntry("deep", 65536);
    }

    @Test
    @DisplayName("详情里的窗口值同样要过合理性校验")
    void detailWindowStillValidated() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"bad\"}]}");
        respond("/v1/models/bad", 200, """
                {"id":"bad","extra_info":{"default_envs":{"max_input_tokens":100000000}}}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.contextWindows()).isEmpty();
    }

    // ------------------------------------------------------------------ 补查的优先级（V26 修复）

    /*
     * 这一组守的是本轮真正的 bug：补查名额按**字母序**截断。
     *
     * 现场证据（2026-10-28，DashScope 183 个模型，按小写字母序）：
     *   qwen3.8-flash             第 148 位  ← 用户实际在用的主模型，被截掉
     *   qwen-max                  第  65 位  ← 用户实际在用的备用，也被截掉
     *   deepseek-v4.1-flash-1017  第   9 位  ← 恰好挤进 40 内
     *
     * 于是症状是「有的模型能自动识别窗口、有的恒为空」，用户读成「功能没做完」，
     * 而它的成因是**模型名首字母**。只测「能取到窗口」的实现对此完全无感 ——
     * 因为取到的那 40 个里确实有几个带窗口的，测试照样绿。
     */

    @Test
    @DisplayName("优先补查清单里的模型：哪怕它排在字母序很靠后也能取到窗口")
    void preferredModelsAreLookedUpEvenWhenFarDownAlphabeticalOrder() {
        // 造一个「用户要的模型排在很后面」的列表：a00..a49 全是填充，
        // 共 50 个，远超 12 个补查名额
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            filler.append("{\"id\":\"a").append(String.format("%02d", i)).append("\"},");
        }
        respond("/v1/models", 200, "{\"data\":[" + filler + "{\"id\":\"qwen3.8-flash\"}]}");
        respond("/v1/models/qwen3.8-flash", 200, """
                {"id":"qwen3.8-flash","extra_info":{"default_envs":{"max_input_tokens":129024}}}
                """);

        // 不传优先清单：字母序截断会把它漏掉
        ModelListResult withoutHint = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");
        assertThat(withoutHint.contextWindows())
                .as("不传优先清单时，第 51 位的模型必然被字母序截断漏掉")
                .doesNotContainKey("qwen3.8-flash");

        // 传上优先清单：它必定被补查到
        ModelListResult withHint =
                service.probe(ModelProtocol.OPENAI, baseUrl, "sk", List.of("qwen3.8-flash"));
        assertThat(withHint.contextWindows()).containsEntry("qwen3.8-flash", 129024);
        assertThat(receivedPaths).contains("/v1/models/qwen3.8-flash");
    }

    @Test
    @DisplayName("优先清单里该端点没有的模型不发请求（白耗预算）")
    void preferredModelsMissingFromEndpointAreNotRequested() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"real-model\"}]}");
        respond("/v1/models/real-model", 200, """
                {"id":"real-model","extra_info":{"default_envs":{"max_input_tokens":32768}}}
                """);

        ModelListResult result = service.probe(
                ModelProtocol.OPENAI, baseUrl, "sk", List.of("ghost-model", "real-model"));

        assertThat(result.contextWindows()).containsEntry("real-model", 32768);
        // 关键：不存在的模型名一次请求都不该发
        assertThat(receivedPaths).doesNotContain("/v1/models/ghost-model");
    }

    @Test
    @DisplayName("优先清单为空时行为不变：仍按字母序兜底补查")
    void emptyPreferredListFallsBackToAlphabetical() {
        respond("/v1/models", 200, "{\"data\":[{\"id\":\"b-model\"},{\"id\":\"a-model\"}]}");
        respond("/v1/models/a-model", 200, """
                {"id":"a-model","extra_info":{"default_envs":{"max_input_tokens":8192}}}
                """);

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk", List.of());

        assertThat(result.contextWindows()).containsEntry("a-model", 8192);
    }

    // ------------------------------------------------------------------ 补查顺序（纯函数）

    /*
     * 顺序本身要独立可测：上面那几条端到端测试证明「偏好生效了」，
     * 但说不清「名额怎么分」。而名额分配是最容易写错的一环 ——
     * 稍不注意就会变成「优先清单里的 20 个 + 字母序前 40 个 = 60 个请求」，
     * 直接打爆预算。
     */

    @Test
    @DisplayName("补查顺序：优先名单在前、其余按原序补满，总数不超 12")
    void orderedTargetsPutsPreferredFirstWithoutExceedingLimit() {
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add("m" + String.format("%03d", i));
        }

        List<String> ordered =
                ModelListProbeService.orderedDetailTargets(all, List.of("m099", "m050"));

        // 优先的两个排最前，且顺序照用户的清单（不被重排）
        assertThat(ordered).startsWith("m099", "m050");
        // 总数硬卡 12：名额本身按优先级分配，多查只会白撞限流
        assertThat(ordered).hasSize(12);
        // 不重复：优先项不会在兜底段再出现一次
        assertThat(ordered).doesNotHaveDuplicates();
        // 兜底段仍按原序（m000 起），证明没把剩余名额打乱
        assertThat(ordered.get(2)).isEqualTo("m000");
    }

    @Test
    @DisplayName("补查顺序：优先名单超出 12 个时只取前 12 个")
    void orderedTargetsCapsPreferredAtLimit() {
        List<String> all = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            all.add("m" + String.format("%03d", i));
        }
        List<String> hugePreferred = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            hugePreferred.add("m" + String.format("%03d", i + 50));
        }

        List<String> ordered = ModelListProbeService.orderedDetailTargets(all, hugePreferred);

        // 12 个名额全部被优先名单占掉（m050~m061），一个兜底名额都不剩 ——
        // 这是刻意的：优先名单就是用户真正关心的，它该吃满所有名额
        assertThat(ordered).containsExactlyElementsOf(hugePreferred.subList(0, 12));
    }

    @Test
    @DisplayName("补查顺序：空列表与 null 都返回空，不抛异常")
    void orderedTargetsHandlesEmptyInputs() {
        assertThat(ModelListProbeService.orderedDetailTargets(List.of(), List.of("x"))).isEmpty();
        assertThat(ModelListProbeService.orderedDetailTargets(null, List.of("x"))).isEmpty();
        assertThat(ModelListProbeService.orderedDetailTargets(List.of("a"), null))
                .containsExactly("a");
    }

    @Test
    @DisplayName("详情接口返回 429 时立刻收手：不再对后续模型发请求")
    void rateLimitStopsFurtherDetailLookups() {
        // 列表里放 12 个模型（正好吃满名额）
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            list.append("{\"id\":\"r").append(i).append("\"},");
        }
        list.append("{\"id\":\"r11\"}");
        respond("/v1/models", 200, "{\"data\":[" + list + "]}");
        /*
         * 详情请求统一 429。
         *
         * <p>桩按**精确路径**注册 context，`/v1/models/r` 不会匹配
         * `/v1/models/r0`，所以这里必须逐个注册 —— 或者用 `/` 兜底。
         * 用 `/` 会把列表请求也吃掉，所以逐个注册更清楚。
         */
        for (int i = 0; i < 12; i++) {
            respond("/v1/models/r" + i, 429, "{\"error\":{\"code\":\"limit_requests\"}}");
        }

        ModelListResult result = service.probe(ModelProtocol.OPENAI, baseUrl, "sk");

        assertThat(result.ok()).isTrue();
        assertThat(result.contextWindows()).isEmpty();
        /*
         * 关键断言：被限流后不该把 12 个请求全发完。
         *
         * 并发度 4 且共享 cursor + throttled 标志，最坏情况下初始那 4 个
         * 都已发出，但它们一看标志就退。这里断言「明显少于 12」而不是精确值
         * —— 并发时序不定，精确断言会变成 flaky 测试。
         */
        long detailCalls = receivedPaths.stream().filter(p -> p.startsWith("/v1/models/r")).count();
        assertThat(detailCalls).isLessThan(12L);
    }
}
