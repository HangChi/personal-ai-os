package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模型列表探测的**纯逻辑**部分：URL 拼接、鉴权头、协议解析、前置条件。
 *
 * <h2>为什么这几件事值得单测</h2>
 * 它们最容易静默出错、又最容易在本地「看起来正常」：
 * <ul>
 *   <li><b>URL 拼错</b> → 端点返回 404 或一个 HTML 页面，界面上只会说
 *       「没解析到模型」，而真实原因（多了一个 /v1、少了一个 /v1）不可见；</li>
 *   <li><b>鉴权头错</b> → Anthropic 只认 {@code x-api-key}，带 Bearer 是 401。
 *       这类错误在日志里只表现为「认证失败」，看不出是头写错了。</li>
 * </ul>
 * 都不需要真发请求，所以用纯函数测，不引入 HTTP 桩。
 *
 * <p>刻意**不用 {@code @Nested}**：本项目的 surefire 配置下嵌套类不被执行
 * （实测外层报 {@code Tests run: 0}），写成扁平结构才不会静默漏跑。
 */
@DisplayName("ModelListProbeService 的 URL 与请求头构造")
class ModelListProbeServiceTest {

    // ------------------------------------------------------------------ OpenAI 路径

    @Test
    @DisplayName("OpenAI：base 不带 /v1 时补上")
    void openAiAppendsV1() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OPENAI, "https://api.openai.com").toString())
                .isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    @DisplayName("OpenAI：base 已带 /v1 时不重复")
    void openAiKeepsExistingV1() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OPENAI, "https://api.openai.com/v1").toString())
                .isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    @DisplayName("末尾斜杠与前后空白都被吃掉")
    void trimsTrailingSlashAndSpaces() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OPENAI, "  https://x.example.com/v1///  ").toString())
                .isEqualTo("https://x.example.com/v1/models");
    }

    @Test
    @DisplayName("带路径前缀的兼容端点保留前缀（DashScope 兼容模式）")
    void keepsPathPrefix() {
        assertThat(ModelListProbeService.resolveUri(
                ModelProtocol.OPENAI, "https://dashscope.aliyuncs.com/compatible-mode/v1").toString())
                .isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1/models");
    }

    @Test
    @DisplayName("根路径末尾的 /v1 只保留一个")
    void singleV1() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OPENAI, "https://x.example.com/v1/").toString())
                .isEqualTo("https://x.example.com/v1/models");
    }

    // ------------------------------------------------------------------ Anthropic 路径

    @Test
    @DisplayName("Anthropic：官方根地址补 /v1/models")
    void anthropicAppendsV1Models() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.ANTHROPIC, "https://api.anthropic.com").toString())
                .isEqualTo("https://api.anthropic.com/v1/models");
    }

    @Test
    @DisplayName("Anthropic：已写 /v1 时不重复（/v1/v1/models 会 404）")
    void anthropicDoesNotDoubleV1() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.ANTHROPIC, "https://api.anthropic.com/v1").toString())
                .isEqualTo("https://api.anthropic.com/v1/models");
    }

    // ------------------------------------------------------------------ Ollama 路径

    @Test
    @DisplayName("Ollama：模型列表在根路径的 /api/tags，不是 /v1/models")
    void ollamaUsesApiTags() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OLLAMA, "http://localhost:11434").toString())
                .isEqualTo("http://localhost:11434/api/tags");
    }

    @Test
    @DisplayName("Ollama：用户误填了 /v1 也要剥掉，否则 /v1/api/tags 是 404")
    void ollamaStripsV1() {
        assertThat(ModelListProbeService.resolveUri(ModelProtocol.OLLAMA, "http://localhost:11434/v1").toString())
                .isEqualTo("http://localhost:11434/api/tags");
    }

    // ------------------------------------------------------------------ 鉴权头

    @Test
    @DisplayName("OpenAI 用 Bearer")
    void openAiUsesBearer() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.OPENAI, URI.create("https://api.openai.com/v1/models"), "sk-abc");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer sk-abc");
    }

    @Test
    @DisplayName("Anthropic 用 x-api-key 且必须带 anthropic-version（缺了会 400）")
    void anthropicUsesApiKeyAndVersion() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.ANTHROPIC, URI.create("https://api.anthropic.com/v1/models"), "sk-ant-xyz");

        assertThat(request.headers().firstValue("x-api-key")).contains("sk-ant-xyz");
        assertThat(request.headers().firstValue("anthropic-version")).contains("2023-06-01");
        // 绝对不能同时带 Bearer：Anthropic 会因鉴权方式冲突而拒绝
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    @DisplayName("Ollama 无密钥时不带鉴权头")
    void ollamaWithoutKeyHasNoAuth() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.OLLAMA, URI.create("http://localhost:11434/api/tags"), null);
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    @DisplayName("Ollama 有密钥时带 Bearer（套了反代的情形）")
    void ollamaWithKeyUsesBearer() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.OLLAMA, URI.create("http://localhost:11434/api/tags"), "proxy-token");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer proxy-token");
    }

    @Test
    @DisplayName("全都是 GET，且要求 JSON 响应")
    void alwaysGetJson() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.OPENAI, URI.create("https://x.example.com/v1/models"), "k");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.headers().firstValue("Accept")).contains("application/json");
    }

    @Test
    @DisplayName("密钥前后空白被去掉（粘贴时常带换行）")
    void trimsKeyWhitespace() {
        HttpRequest request = ModelListProbeService.buildRequest(
                ModelProtocol.OPENAI, URI.create("https://x.example.com/v1/models"), "  sk-abc\n");
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer sk-abc");
    }

    // ------------------------------------------------------------------ 协议解析

    @Test
    @DisplayName("协议为 null / 空白时回落 OPENAI")
    void blankProtocolFallsBack() {
        assertThat(ModelProtocol.parse(null)).isEqualTo(ModelProtocol.OPENAI);
        assertThat(ModelProtocol.parse("   ")).isEqualTo(ModelProtocol.OPENAI);
    }

    @Test
    @DisplayName("协议大小写与前后空白都容忍")
    void protocolCaseInsensitive() {
        assertThat(ModelProtocol.parse(" anthropic ")).isEqualTo(ModelProtocol.ANTHROPIC);
        assertThat(ModelProtocol.parse("OLLAMA")).isEqualTo(ModelProtocol.OLLAMA);
    }

    @Test
    @DisplayName("无法识别的协议回落 OPENAI 而不是抛异常")
    void unknownProtocolFallsBack() {
        // 拼错的协议不该让「保存配置」整个失败 —— 那是与用户目的无关的错误
        assertThat(ModelProtocol.parse("gemini")).isEqualTo(ModelProtocol.OPENAI);
    }

    // ------------------------------------------------------------------ 前置条件

    @Test
    @DisplayName("端点为空时给出提示")
    void requiresBaseUrl() {
        assertThat(ModelListProbeService.blockedReason(ModelProtocol.OPENAI, "  ", "sk")).isPresent();
    }

    @Test
    @DisplayName("非 Ollama 且无密钥时给出提示")
    void requiresKeyForNonOllama() {
        assertThat(ModelListProbeService.blockedReason(ModelProtocol.OPENAI, "https://x.example.com", null))
                .isPresent();
        assertThat(ModelListProbeService.blockedReason(ModelProtocol.ANTHROPIC, "https://x.example.com", " "))
                .isPresent();
    }

    @Test
    @DisplayName("Ollama 不需要密钥")
    void ollamaNeedsNoKey() {
        assertThat(ModelListProbeService.blockedReason(ModelProtocol.OLLAMA, "http://localhost:11434", null))
                .isEmpty();
    }

    @Test
    @DisplayName("条件齐备时放行")
    void passesWhenReady() {
        assertThat(ModelListProbeService.blockedReason(ModelProtocol.OPENAI, "https://x.example.com", "sk"))
                .isEmpty();
    }

    // ------------------------------------------------------------------ 网络错误文案

    /**
     * {@code describeNetworkError} 是私有的，但它决定了用户看到的全部网络类报错文案
     * —— 「Tunnel failed, got: 503」原样透出去等于没说，用户看不出是自己代理的问题
     * 还是端点的问题。用反射直接测这一层，比为了它放开可见性更划算。
     */
    private static String describe(Throwable t) throws Exception {
        var method = ModelListProbeService.class.getDeclaredMethod("describeNetworkError", Throwable.class);
        method.setAccessible(true);
        return (String) method.invoke(null, t);
    }

    @Test
    @DisplayName("代理/隧道失败不把底层原文直接丢给用户")
    void tunnelFailureIsHumanized() throws Exception {
        String msg = describe(new java.io.IOException("Tunnel failed, got: 503"));
        assertThat(msg).contains("代理").doesNotContain("Tunnel failed");
    }

    @Test
    @DisplayName("其它 IO 异常也带上「网络连接失败」前缀")
    void genericIoIsPrefixed() throws Exception {
        assertThat(describe(new java.io.IOException("Connection reset"))).startsWith("网络连接失败");
    }

    @Test
    @DisplayName("域名解析失败与端口未监听分开表述")
    void networkErrorKindsAreDistinct() throws Exception {
        assertThat(describe(new java.net.UnknownHostException("nope.example"))).contains("域名解析失败");
        assertThat(describe(new java.net.ConnectException("refused"))).contains("连接被拒绝");
    }

    @Test
    @DisplayName("TLS 失败提示可能是自签证书或该用 http://")
    void sslFailureIsReadable() throws Exception {
        assertThat(describe(new javax.net.ssl.SSLHandshakeException("bad cert"))).contains("TLS 握手失败");
    }

    @Test
    @DisplayName("没有 message 的异常退化为类名，不出现 null")
    void emptyMessageFallsBackToClassName() throws Exception {
        String msg = describe(new IllegalStateException());
        assertThat(msg).isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("超长异常消息被截断，不刷满整屏")
    void longMessageIsAbbreviated() throws Exception {
        String msg = describe(new IllegalStateException("x".repeat(500)));
        assertThat(msg.length()).isLessThan(230);
        assertThat(msg).endsWith("…");
    }
}
