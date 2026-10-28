package com.personalos.common.ai.config;

import com.personalos.common.ai.config.dto.TranscriptionResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 语音转文字（ASR）：把录音发给配置好的语音模型，拿回文本。
 *
 * <h2>为什么发 OpenAI 兼容的 /v1/audio/transcriptions</h2>
 * 与聊天链路同样的取舍：项目只认 OpenAI 兼容协议，DashScope、OpenAI、
 * Groq、SiliconFlow、本地 faster-whisper 的 OpenAI 兼容层都实现了这个端点。
 * 换成厂商原生接口就要为每家写一份适配，而收益只是多支持几家不兼容的小众端点。
 *
 * <h2>为什么没有配语音模型时不报错</h2>
 * 语音输入在自托管场景里的定位是「少打点字」，不是核心能力。没配模型时
 * 前端的 {@code ChatVoiceInput} 会回落到浏览器自带的 Web Speech API ——
 * 那条路不需要任何配置就能用。此处返回一个 {@code unavailable} 的结果
 * （而不是抛异常），让前端能凭一个明确的信号去切换，而不是靠错误文案猜。
 *
 * <h2>为什么用虚拟线程卡超时</h2>
 * 与 {@link ModelListProbeService} 同一手法、同一理由：HttpClient 的超时
 * 只覆盖连接建立与单次读写，不限制整个响应读完的耗时。转写是用户盯着
 * 麦克风按钮等结果的同步动作，必须有一个硬上限。
 */
@Service
public class AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionService.class);

    /**
     * 转写超时。
     *
     * <p>比模型列表探测（10 秒）长、比聊天流（120 秒）短得多：
     * 一段几十秒的录音在正常网络上 1~3 秒出结果，30 秒还没回来说明上游卡住了，
     * 让用户继续等只是在拖时间。
     */
    private static final long TIMEOUT_SECONDS = 30;

    /**
     * 单次录音的大小上限（8MB）。
     *
     * <p>与 {@code spring.servlet.multipart.max-file-size} 对齐。按 opus/webm
     * 约 24kbps 估算，8MB 已经超过 40 分钟录音 —— 远超「说一句话」的量级。
     * 超限说明前端出了问题（忘了停止录音），直接拒绝比让上游 413 更好读。
     */
    static final long MAX_AUDIO_BYTES = 8L * 1024 * 1024;

    /**
     * 字段名固定为 {@code file}。
     *
     * <p>OpenAI 的接口规格里这个字段就叫 {@code file}，改名字上游会直接 400。
     * 抽成常量是为了让测试能断言「发出去的字段名是什么」——
     * 这类错误只看代码很难发现，只有对着真实请求检查才可靠。
     */
    static final String FILE_PART_NAME = "file";

    /** 生成模型名的字段名，同上 */
    static final String MODEL_PART_NAME = "model";

    private final ModelConfigService configService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AudioTranscriptionService(ModelConfigService configService,
                                     ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                // 不跟随重定向：与探测服务同因，跟随到登录页时拿到的 HTML
                // 会让「转写失败」的原因变得难以理解
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 当前是否已配置语音模型。
     *
     * <p>前端用它决定麦克风按钮走哪条路：配了就录音上传，没配就用浏览器自带的。
     * 单独暴露而不是让前端读 {@code audioModel != ''}：判断条件将来可能
     * 增加（比如「密钥也得有」），收在一处才不会两边写得不一样。
     */
    public boolean available() {
        return blockReason() == null;
    }

    /**
     * 不能转写的原因；{@code null} 表示可以。
     *
     * <p>返回原因而不是布尔值，是为了让前端能给出「为什么没走自己的模型」
     * 这种可操作的信息，而不是一个沉默的回退。
     */
    public String blockReason() {
        ModelConfigService.ResolvedConfig resolved = currentResolved();
        if (!resolved.hasAudioModel()) {
            return "当前生效的模型配置没有语音模型";
        }
        if (!resolved.hasApiKey()) {
            return "当前生效的模型配置没有 API Key";
        }
        return null;
    }

    /**
     * 转写一段音频。
     *
     * @param audio       音频字节（webm / opus / wav / mp3 均可，由上游识别）
     * @param filename    原始文件名，仅用于给上游一个形式正确的 multipart 头；
     *                    很多端点的解析器要求它带扩展名才会猜对格式
     * @param contentType 音频的 MIME 类型；为空时按 webm 处理
     * @return 转写结果；不可用时 {@code ok=false} 且 {@code unavailable=true}
     */
    public TranscriptionResult transcribe(byte[] audio, String filename, String contentType) {
        long startNanos = System.nanoTime();

        String blockReason = blockReason();
        if (blockReason != null) {
            // 不是错误：这是「没配语音模型」这一正常状态。用 unavailable 区分，
            // 前端据此回落到浏览器识别，而不是弹一个用户不知道该怎么办的错误
            return TranscriptionResult.unavailable(blockReason);
        }
        if (audio == null || audio.length == 0) {
            return TranscriptionResult.failed("录音为空，没有可转写的内容");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            return TranscriptionResult.failed(
                    "录音超过 " + (MAX_AUDIO_BYTES / 1024 / 1024) + "MB，请缩短后重试");
        }

        ModelConfigService.ResolvedConfig resolved = currentResolved();
        URI uri = resolveUri(resolved.baseUrl());
        MultipartBody multipart = buildMultipartBody(
                audio, filename, contentType, resolved.audioModel());

        HttpRequest request = HttpRequest.newBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart.body()))
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Content-Type", "multipart/form-data; boundary=" + multipart.boundary())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + resolved.apiKey().strip())
                .build();

        FutureTask<HttpResponse<String>> task = new FutureTask<>(
                () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        Thread.ofVirtual().name("audio-transcription").start(task);

        try {
            HttpResponse<String> response = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            TranscriptionResult result = interpret(uri, response, latencyMs);
            log.info("语音转写完成：model={}, 音频 {} 字节, 耗时 {}ms, ok={}",
                    resolved.audioModel(), audio.length, latencyMs, result.ok());
            return result;
        } catch (TimeoutException ex) {
            task.cancel(true);
            log.warn("语音转写超时：{} {}s", uri, TIMEOUT_SECONDS);
            return TranscriptionResult.failed(
                    "转写超时（超过 " + TIMEOUT_SECONDS + " 秒），请检查端点地址与网络");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("语音转写失败：{} — {}", uri, cause.toString());
            return TranscriptionResult.failed(describeNetworkError(cause));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return TranscriptionResult.failed("转写被中断");
        }
    }

    // ------------------------------------------------------------------ 请求构造

    /**
     * 拼出转写接口的 URL。
     *
     * <p>与 {@link ModelListProbeService#resolveUri} 同一套「baseUrl 写法五花八门」
     * 的处理：用户可能填 {@code https://api.openai.com}、{@code .../v1}、
     * 或末尾带斜杠。三种都得落到 {@code .../v1/audio/transcriptions}。
     *
     * <p>抽成包级静态方法是为了单测能直接断言路径 —— 这类拼接错误在运行期
     * 表现为 404，而 404 的原因可能是路径错、也可能是端点不支持，
     * 只看日志分不清。
     */
    static URI resolveUri(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String base = trimmed.endsWith("/v1") ? trimmed : trimmed + "/v1";
        return URI.create(base + "/audio/transcriptions");
    }

    /**
     * 手工拼 multipart/form-data 请求体。
     *
     * <h2>为什么不引入 multipart 库</h2>
     * 只需要发两个文本字段 + 一个文件，手拼不到 20 行；而 Spring 的
     * {@code MultipartBodyBuilder} 依赖 WebFlux 客户端栈，
     * 引入它的代价大于收益。
     *
     * <h2>换行必须是 CRLF</h2>
     * 按 RFC 7578，multipart 的分隔行用 {@code \r\n}。只写 {@code \n} 时
     * 多数宽松的实现（含 OpenAI）能接受，但少数严格的解析器会把
     * 整个字段当成一个长字符串 —— 表现为「模型名里多了一堆乱码」。
     *
     * <h2>为什么返回 boundary 而不只返回 body</h2>
     * 两者必须同源：body 里用的是哪个 boundary，Content-Type 头里就得写哪个。
     * 早先用实例字段传递，代价是「本方法不再线程安全」——
     * 两个并发转写会互相覆盖 boundary，症状是概率性的 400。
     * 返回一个 record 让这条依赖关系在类型上就成立。
     */
    private static MultipartBody buildMultipartBody(byte[] audio, String filename,
                                                    String contentType, String modelName) {
        String boundary = "----personalos" + UUID.randomUUID().toString().replace("-", "");
        String safeName = filename == null || filename.isBlank() ? "audio.webm" : filename;
        String safeType = contentType == null || contentType.isBlank() ? "audio/webm" : contentType;

        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 512);
        // 模型名是必填字段：不传时上游会把请求转给它的默认模型，
        // 而那个默认模型往往不存在或没权限，报错信息也指向不到这里
        writeText(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + MODEL_PART_NAME + "\"\r\n\r\n"
                + modelName + "\r\n");
        writeText(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + FILE_PART_NAME + "\"; filename=\""
                + safeName + "\"\r\n"
                + "Content-Type: " + safeType + "\r\n\r\n");
        out.writeBytes(audio);
        writeText(out, "\r\n--" + boundary + "--\r\n");
        return new MultipartBody(out.toByteArray(), boundary);
    }

    /**
     * 拼好的请求体与它对应的 boundary。
     *
     * @param body     multipart 请求体
     * @param boundary 分隔串，必须写进 Content-Type 头
     */
    record MultipartBody(byte[] body, String boundary) {
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ 响应解析

    /**
     * 把响应翻译成结果。
     *
     * <p>成功时 OpenAI 返回 {@code {"text": "..."}}。少数网关把它包在
     * {@code data} 下，这里一并兼容 —— 不兼容的代价是「明明上游成功了，
     * 界面却报解析失败」，而那种问题只能靠抓包才能查清。
     */
    private TranscriptionResult interpret(URI uri, HttpResponse<String> response, long latencyMs) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String reason = switch (status) {
                case 400 -> "请求被拒绝（400）：该模型可能不支持转写，或音频格式不被接受";
                case 401 -> "认证失败（401）：API Key 无效";
                case 403 -> "无权限（403）：该密钥不能使用这个语音模型";
                case 404 -> "接口不存在（404）：" + uri.getPath()
                        + " 在该端点上不存在，可能它不支持 OpenAI 兼容的转写接口";
                case 413 -> "音频过大（413）：请缩短录音";
                case 429 -> "被限流（429）：请求过于频繁";
                default -> "转写失败（HTTP " + status + "）";
            };
            String body = trim(response.body());
            return TranscriptionResult.failed(body.isEmpty() ? reason : reason + "；响应：" + body);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException ex) {
            return TranscriptionResult.failed("响应不是合法 JSON，无法解析转写结果");
        }
        if (root == null || root.isNull()) {
            return TranscriptionResult.failed("响应为空，没有拿到转写文本");
        }

        JsonNode textNode = root.path("text");
        if (!textNode.isString()) {
            textNode = root.path("data").path("text");
        }
        if (!textNode.isString()) {
            return TranscriptionResult.failed("响应里没有 text 字段，该端点可能不兼容 OpenAI 转写格式");
        }
        String text = textNode.asString().strip();
        if (text.isEmpty()) {
            // 空文本不是失败：用户可能只是没说话就按了停止。前端据此给
            // 「没听清」而不是报错，两者对用户的后续动作完全不同。
            return TranscriptionResult.empty();
        }
        return new TranscriptionResult(true, text, latencyMs, null, false);
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 取当前生效配置；与聊天链路共用同一套解析（含多配置回落）。
     *
     * <p>用 {@code current()} 而不是 {@code resolve()}：后者只看 V20 单行表，
     * 而用户配的语音模型在 V21 多配置表里。
     */
    private ModelConfigService.ResolvedConfig currentResolved() {
        return configService.current();
    }

    private static String trim(String body) {
        if (body == null) {
            return "";
        }
        String flattened = body.replaceAll("\\s+", " ").strip();
        return flattened.length() <= 200 ? flattened : flattened.substring(0, 200) + "…";
    }

    /**
     * 网络层异常转成一句话。
     *
     * <p>与 {@link ModelListProbeService} 保持一致的分类：用户看到「域名解析失败」
     * 与「连接被拒绝」时的下一步动作完全不同，而原始异常消息通常一样晦涩。
     */
    private static String describeNetworkError(Throwable cause) {
        if (cause instanceof java.net.UnknownHostException) {
            return "域名解析失败：请检查端点地址拼写或本机 DNS";
        }
        if (cause instanceof java.net.ConnectException) {
            return "连接被拒绝：该端口可能没有服务在监听";
        }
        if (cause instanceof java.net.http.HttpTimeoutException
                || cause instanceof java.net.SocketTimeoutException) {
            return "连接超时：端点不可达或网络受限";
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败：该端点可能使用自签证书，或地址应写成 http://";
        }
        if (cause instanceof IOException) {
            String raw = cause.getMessage() == null ? "" : cause.getMessage();
            if (raw.toLowerCase(java.util.Locale.ROOT).contains("tunnel")) {
                return "经由代理/隧道的连接失败：请检查代理设置后重试";
            }
            return "网络连接失败：" + (raw.isEmpty() ? cause.getClass().getSimpleName() : raw);
        }
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + "：" + message;
    }
}
