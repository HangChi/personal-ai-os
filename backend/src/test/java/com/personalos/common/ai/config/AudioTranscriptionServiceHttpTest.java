package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.ai.config.dto.TranscriptionResult;
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
 * 语音转写的**端到端**行为：真的发一次 multipart 请求，打到本地桩上。
 *
 * <h2>为什么这一层不能省</h2>
 * {@link AudioTranscriptionServiceTest} 只测 URL 拼接与 pure 逻辑，
 * 覆盖不到 {@code transcribe()} 的主流程。而手拼 multipart 恰恰是本类最可能
 * 「单测全绿、线上 400」的地方：
 * <ul>
 *   <li>boundary 写进了 body 但没写进 Content-Type 头 —— 服务端解析不出任何字段；</li>
 *   <li>字段名写成 {@code audio} 而不是 {@code file} —— OpenAI 直接 400；</li>
 *   <li>换行用了 {@code \n} 而不是 {@code \r\n} —— 严格的解析器会把整个
 *       请求体当成一个长字符串；</li>
 *   <li>模型名没带上 —— 上游转给它的默认模型，报错指向不到这里。</li>
 * </ul>
 * 这四条都无法从代码「看出来」，只能让服务真的发一次、在桩上把收到的
 * 原始字节读出来核对。
 *
 * <p>刻意**不用 {@code @Nested}**：本项目 surefire 配置下嵌套类不被执行。
 */
@DisplayName("AudioTranscriptionService 的真实请求行为")
class AudioTranscriptionServiceHttpTest {

    private HttpServer server;
    private String baseUrl;
    private AudioTranscriptionService service;

    /** 桩最近一次收到的请求路径 */
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    /** 桩最近一次收到的请求头容器，用来断言鉴权与 Content-Type */
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();

    /** 桩最近一次收到的原始请求体（UTF-8 解码，音频用 ASCII 占位所以可读） */
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    /** 桩收到的方法 */
    private final AtomicReference<String> lastMethod = new AtomicReference<>();

    /** 所有被命中的路径 */
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    /** 用于伪造「生效配置」的桩返回 */
    private String audioModel = "paraformer-v2";

    private String apiKey = "sk-test";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ModelConfigService configService = new StubModelConfigService();
        service = new AudioTranscriptionService(configService, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ------------------------------------------------------------------ 桩

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> {
            receivedPaths.add(exchange.getRequestURI().getPath());
            lastPath.set(exchange.getRequestURI().getPath());
            lastMethod.set(exchange.getRequestMethod());
            lastExchange.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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

    /**
     * 只替换 {@code current()} 的配置服务。
     *
     * <p>为什么用匿名子类而不是 Mockito：本测试关心的是「真正发出去的请求
     * 长什么样」，配置来源只是一个入参。用子类把入参钉死，比引入 mock
     * 框架的语法噪音更少。
     *
     * <p>必须覆盖 {@code current()} 而不是 {@code resolve()}：
     * {@code AudioTranscriptionService} 走的是前者（多配置表里生效的那行），
     * 覆盖后者会被静默绕过 —— 症状是「改了 audioModel 但测试还按旧值跑」。
     * 父类构造器拿不到仓库也能构造成功（全传 null），因为本类从不调用它们。
     */
    private class StubModelConfigService extends ModelConfigService {
        StubModelConfigService() {
            // 全 null 合法：本子类唯一被调用的方法是 current()，其余不会被触发
            super((com.personalos.common.ai.config.AiModelConfigRepository) null,
                    null, null, java.time.Clock.systemUTC(), null,
                    baseUrl, apiKey, "gpt-4o-mini", "text-embedding-3-small", null);
        }

        @Override
        public ResolvedConfig current() {
            return new ResolvedConfig(baseUrl, apiKey, "gpt-4o-mini", null,
                    "text-embedding-3-small",
                    audioModel == null || audioModel.isBlank() ? null : audioModel,
                    apiKey.isBlank() ? null : 1, java.time.Instant.now(), java.util.Map.of());
        }
    }

    // ------------------------------------------------------------------ 成功路径

    @Test
    @DisplayName("成功：打到 /v1/audio/transcriptions，带 Bearer 与正确 boundary，返回 text")
    void transcribesSuccessfully() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"今天天气不错\"}");

        TranscriptionResult result = service.transcribe(
                "FAKEAUDIO".getBytes(StandardCharsets.UTF_8), "clip.webm", "audio/webm");

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("今天天气不错");
        assertThat(result.unavailable()).isFalse();
        assertThat(lastPath.get()).isEqualTo("/v1/audio/transcriptions");
        assertThat(lastMethod.get()).isEqualTo("POST");
        assertThat(header("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(header("Accept")).contains("application/json");
    }

    @Test
    @DisplayName("Content-Type 里的 boundary 必须与请求体里用的一致")
    void contentTypeBoundaryMatchesBody() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"好\"}");

        service.transcribe("X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        String contentType = header("Content-Type");
        assertThat(contentType).startsWith("multipart/form-data; boundary=");
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        // 请求体必须以 --<boundary> 开头，否则服务端连第一个字段都找不到
        assertThat(lastBody.get()).startsWith("--" + boundary);
        assertThat(lastBody.get()).contains("--" + boundary + "--");
        // 两处都出现说明头与体同源；不一致是本类最容易犯的错
        assertThat(boundary).isNotBlank();
    }

    @Test
    @DisplayName("请求体包含 file 与 model 两个字段，且换行是 CRLF")
    void bodyHasFileAndModelParts() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"好\"}");

        service.transcribe("AUDIOBYTES".getBytes(StandardCharsets.UTF_8), "voice.webm", "audio/webm");

        String body = lastBody.get();
        assertThat(body).contains("name=\"file\"");
        assertThat(body).contains("filename=\"voice.webm\"");
        assertThat(body).contains("Content-Type: audio/webm");
        assertThat(body).contains("name=\"model\"");
        assertThat(body).contains("paraformer-v2");
        assertThat(body).contains("AUDIOBYTES");
        // 只写 \n 时严格的解析器会把整块当一个字段 —— 断言用的是 CRLF
        assertThat(body).contains("\r\n");
    }

    @Test
    @DisplayName("缺 filename 时兜底成 audio.webm（部分端点靠扩展名猜格式）")
    void defaultsFilename() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"好\"}");

        service.transcribe("X".getBytes(StandardCharsets.UTF_8), null, null);

        assertThat(lastBody.get()).contains("filename=\"audio.webm\"");
        assertThat(lastBody.get()).contains("Content-Type: audio/webm");
    }

    // ------------------------------------------------------------------ 失败与回落

    @Test
    @DisplayName("没配语音模型 → unavailable，且一次请求都不发")
    void unavailableWhenNoAudioModel() {
        audioModel = "";
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"不该被调用\"}");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.unavailable()).isTrue();
        assertThat(result.error()).contains("语音模型");
        // 关键：不可用时不发请求。发了就说明判断放在了网络调用之后
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("401 → 可读的认证失败文案")
    void unauthorizedIsReadable() {
        respond("/v1/audio/transcriptions", 401, "{\"error\":{\"message\":\"invalid key\"}}");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.unavailable()).isFalse();
        assertThat(result.error()).contains("401");
        assertThat(result.error()).contains("API Key");
    }

    @Test
    @DisplayName("404 → 指出这个端点没有兼容的转写接口")
    void notFoundMentionsPath() {
        respond("/v1/audio/transcriptions", 404, "not found");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("404");
        assertThat(result.error()).contains("/v1/audio/transcriptions");
    }

    @Test
    @DisplayName("响应不是 JSON → 明确的解析失败，不抛异常")
    void nonJsonBodyIsReadable() {
        respond("/v1/audio/transcriptions", 200, "<html>login</html>");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("JSON");
    }

    @Test
    @DisplayName("响应缺 text 字段 → 指出端点不兼容 OpenAI 转写格式")
    void missingTextField() {
        respond("/v1/audio/transcriptions", 200, "{\"result\":\"hello\"}");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("text");
    }

    @Test
    @DisplayName("text 为空串 → ok=true 但无内容（用户没说话，不是故障）")
    void emptyTextIsNotFailure() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"   \"}");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEmpty();
        assertThat(result.error()).isNull();
    }

    @Test
    @DisplayName("兼容包在 data.text 下的形状")
    void parsesNestedDataText() {
        respond("/v1/audio/transcriptions", 200, "{\"data\":{\"text\":\"嵌套形状\"}}");

        TranscriptionResult result = service.transcribe(
                "X".getBytes(StandardCharsets.UTF_8), "a.webm", "audio/webm");

        assertThat(result.ok()).isTrue();
        assertThat(result.text()).isEqualTo("嵌套形状");
    }

    @Test
    @DisplayName("空音频 → 不请求上游，直接给出可读原因")
    void emptyAudioRejectedLocally() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"不该被调用\"}");

        TranscriptionResult result = service.transcribe(new byte[0], "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("录音为空");
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    @DisplayName("超过 8MB → 本地拒绝，不浪费一次上传")
    void oversizedAudioRejectedLocally() {
        respond("/v1/audio/transcriptions", 200, "{\"text\":\"不该被调用\"}");

        byte[] huge = new byte[(int) AudioTranscriptionService.MAX_AUDIO_BYTES + 1];
        TranscriptionResult result = service.transcribe(huge, "a.webm", "audio/webm");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("8MB");
        assertThat(receivedPaths).isEmpty();
    }
}
