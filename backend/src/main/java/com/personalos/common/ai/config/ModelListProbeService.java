package com.personalos.common.ai.config;

import com.personalos.common.ai.config.dto.ModelListResult;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
 * 探测某个端点支持哪些模型（cc-switch 的「获取模型列表」）。
 *
 * <h2>为什么自己发 HTTP 而不走 Spring AI</h2>
 * Spring AI 2.0.1 的 openai 模块只保留 ChatModel / EmbeddingModel 等
 * 模型类，没有「列出模型」的封装（{@code src} 里已无 model-listing 相关类）。
 * 而三家的列出接口在路径、鉴权头、响应字段上互不相同，与其为了三次
 * GET 请求引入新依赖，不如用 JDK 自带的 {@link java.net.http.HttpClient}。
 *
 * <h2>为什么探测失败不抛异常</h2>
 * 「这个端点不提供模型列表」是一种**正常结果**，不是错误：
 * 大量自建与中转端点根本没有实现 {@code /models}，但它们能正常聊天。
 * 若抛异常，前端就得把「探测失败」与「配置写错」当成同一件事，而这两者
 * 的处理方式完全不同 —— 前者照常手打模型名即可。因此统一返回
 * {@link ModelListResult}，用 {@code ok} 字段表达成败，与
 * {@link ModelConnectionTestService} 的口径一致。
 *
 * <h2>为什么用虚拟线程卡超时</h2>
 * 与 {@code ModelConnectionTestService} 同一手法。{@link HttpClient} 的
 * 超时只覆盖连接建立与单次读写，不限制整个响应体读完的耗时；一个
 * 慢慢吐字节的端点能让请求挂到 TCP 超时。用户是盯着按钮等的，必须硬卡。
 */
@Service
public class ModelListProbeService {

    private static final Logger log = LoggerFactory.getLogger(ModelListProbeService.class);

    /**
     * 探测超时。
     *
     * <p>比连通性测试的 20 秒短：模型列表通常是一个静态 JSON，不涉及推理，
     * 正常在 1 秒内返回。10 秒还没回来说明端点不通或极慢，再等没有意义。
     */
    private static final long TIMEOUT_SECONDS = 10;

    /** Anthropic 要求显式声明 API 版本；取当前稳定版，写死避免跟随上游漂移 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 单个端点最多保留的模型数：防止某个端点返回上万条把界面拖死 */
    private static final int MAX_MODELS = 500;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ModelListProbeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                // 不自动跟随重定向：中转站常把 /models 302 到别处，
                // 跟随后拿到的可能是 HTML 登录页，解析失败反而更难排查。
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 探测模型列表。
     *
     * @param protocol 协议类型，决定路径与鉴权方式
     * @param baseUrl  端点根地址（用户填的那个，可能带或不带 /v1）
     * @param apiKey   API Key；Ollama 可为空
     * @return 探测结果；失败时 {@code ok=false} 且带可读原因
     */
    public ModelListResult probe(ModelProtocol protocol, String baseUrl, String apiKey) {
        long startNanos = System.nanoTime();

        if (baseUrl == null || baseUrl.isBlank()) {
            return ModelListResult.failed("请先填写端点地址");
        }
        String normalized = baseUrl.strip();
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return ModelListResult.failed("端点地址必须以 http:// 或 https:// 开头");
        }

        ModelProtocol effective = protocol == null ? ModelProtocol.OPENAI : protocol;
        // Ollama 本地默认不需要密钥，但填了也照带（部分反代会校验）
        boolean needsKey = effective != ModelProtocol.OLLAMA;
        if (needsKey && (apiKey == null || apiKey.isBlank())) {
            return ModelListResult.failed("请先填写 API Key");
        }

        URI uri;
        HttpRequest request;
        try {
            uri = resolveUri(effective, normalized);
            request = buildRequest(effective, uri, apiKey);
        } catch (IllegalArgumentException ex) {
            return ModelListResult.failed("端点地址无法解析：" + ex.getMessage());
        }

        FutureTask<HttpResponse<String>> task =
                new FutureTask<>(() -> httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        Thread.ofVirtual().name("model-list-probe").start(task);

        try {
            HttpResponse<String> response = task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
            return interpret(effective, uri, response, latencyMs);
        } catch (TimeoutException ex) {
            task.cancel(true);
            log.warn("模型列表探测超时：{} {}s", uri, TIMEOUT_SECONDS);
            return ModelListResult.failed("请求超时（超过 " + TIMEOUT_SECONDS + " 秒），请检查端点地址与网络");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            log.warn("模型列表探测失败：{} — {}", uri, cause.toString());
            return ModelListResult.failed(describeNetworkError(cause));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return ModelListResult.failed("探测被中断");
        }
    }

    // ------------------------------------------------------------------ 请求构造

    /**
     * 按协议拼出模型列表的 URL。
     *
     * <h3>路径拼接是这里最容易出错的地方</h3>
     * 用户填的 baseUrl 写法五花八门，都必须能工作：
     * <ul>
     *   <li>OpenAI：{@code https://api.openai.com/v1} → {@code .../v1/models}；
     *       填 {@code https://api.openai.com} 也要得到 {@code .../v1/models}
     *       （大多数人不会写 /v1，而缺了它就是 404）。</li>
     *   <li>Anthropic：baseUrl 通常已是 {@code https://api.anthropic.com}，
     *       要补 {@code /v1/models}；若用户已经把 /v1 写进去，不能变成
     *       {@code /v1/v1/models}。</li>
     *   <li>Ollama：模型列表在**根路径**下的 {@code /api/tags}，与 /v1 无关。
     *       用户填 {@code http://localhost:11434} 或 {@code http://localhost:11434/v1}
     *       都应指向 {@code http://localhost:11434/api/tags}。</li>
     * </ul>
     */
    static URI resolveUri(ModelProtocol protocol, String baseUrl) {
        String trimmed = stripTrailingSlash(baseUrl.trim());
        return switch (protocol) {
            case OPENAI -> URI.create(ensureSuffix(trimmed, "/v1") + "/models");
            case ANTHROPIC -> URI.create(ensureSuffix(trimmed, "/v1") + "/models");
            case OLLAMA -> URI.create(stripV1Suffix(trimmed) + "/api/tags");
        };
    }

    /**
     * 按协议构造请求：路径之外，三家的鉴权头也不一样。
     *
     * <ul>
     *   <li>OpenAI 系：{@code Authorization: Bearer <key>}</li>
     *   <li>Anthropic：{@code x-api-key: <key>} + {@code anthropic-version: 2023-06-01}。
     *       只带 Bearer 会 401 —— 这是接 Anthropic 时最常见的错误。</li>
     *   <li>Ollama：不鉴权；有 key 时加 Bearer（兼容套了反代的部署）。</li>
     * </ul>
     */
    static HttpRequest buildRequest(ModelProtocol protocol, URI uri, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .header("Accept", "application/json");

        String key = apiKey == null ? "" : apiKey.strip();
        switch (protocol) {
            case OPENAI -> {
                if (!key.isEmpty()) {
                    builder.header("Authorization", "Bearer " + key);
                }
            }
            case ANTHROPIC -> {
                // 必须两个头都带：x-api-key 无 Bearer 前缀，版本头缺失会 400
                builder.header("x-api-key", key);
                builder.header("anthropic-version", ANTHROPIC_VERSION);
            }
            case OLLAMA -> {
                if (!key.isEmpty()) {
                    builder.header("Authorization", "Bearer " + key);
                }
            }
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ 响应解析

    /**
     * 把 HTTP 响应翻译成结果。
     *
     * <p>非 2xx 时优先给出状态码对应的可操作提示，而不是把整坨响应体
     * 丢给用户 —— 但会附上截断后的响应体片段，方便排查自建端点。
     */
    private ModelListResult interpret(ModelProtocol protocol, URI uri,
                                      HttpResponse<String> response, long latencyMs) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String reason = switch (status) {
                case 401 -> "认证失败（401）：API Key 无效，或该端点不使用这种鉴权方式";
                case 403 -> "无权限（403）：该密钥不能列出模型";
                case 404 -> "接口不存在（404）：" + uri.getPath() + " 在该端点上不存在，"
                        + "可能它不提供模型列表接口";
                case 429 -> "被限流（429）：请求过于频繁";
                default -> "请求失败（HTTP " + status + "）";
            };
            String body = trim(response.body());
            String message = body.isEmpty() ? reason : reason + "；响应：" + body;
            // 404 与 405 属于「这个端点压根没做这个接口」，是正常情况：
            // 用户不需要改任何配置，手填模型名即可
            if (status == 404 || status == 405) {
                return ModelListResult.unsupported(message);
            }
            return ModelListResult.failed(message);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (RuntimeException ex) {
            // 返回 HTML 通常意味着被重定向到登录页/门户页 —— 该端点没有可用的列表接口
            return ModelListResult.unsupported("响应不是合法 JSON（该端点可能返回了 HTML 页面）："
                    + trim(response.body()));
        }
        if (root == null || root.isNull()) {
            return ModelListResult.failed("响应为空");
        }

        // OpenAI / Anthropic 都用 data[].id；Ollama 用 models[].name
        List<String> ids = new ArrayList<>();
        if (protocol == ModelProtocol.OLLAMA) {
            collectIds(root.path("models"), "name", ids);
            // 兜底：某些版本/反代在 /api/tags 也吐 OpenAI 形状
            if (ids.isEmpty()) {
                collectIds(root.path("data"), "id", ids);
            }
        } else {
            collectIds(root.path("data"), "id", ids);
            if (ids.isEmpty()) {
                // 少数中转站直接返回数组，或把列表放在 models 下
                if (root.isArray()) {
                    collectIds(root, "id", ids);
                } else {
                    collectIds(root.path("models"), "id", ids);
                }
            }
        }

        if (ids.isEmpty()) {
            return ModelListResult.unsupported("接口有响应但没有解析到任何模型名，"
                    + "可能该端点不提供模型列表。可以手动填写模型名。");
        }

        List<String> sorted = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .sorted(Comparator.comparing(String::toLowerCase, String.CASE_INSENSITIVE_ORDER))
                .limit(MAX_MODELS)
                .toList();
        log.info("模型列表探测成功：{} {} → {} 个模型，耗时 {}ms", protocol, uri, sorted.size(), latencyMs);
        return new ModelListResult(true, sorted, latencyMs, null);
    }

    /** 从数组节点的每个元素里取指定字段；缺失或非文本字段直接跳过 */
    private static void collectIds(JsonNode array, String field, List<String> sink) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode node : array) {
            JsonNode value = node.path(field);
            if (value.isString()) {
                sink.add(value.asString());
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    private static String stripTrailingSlash(String value) {
        String out = value;
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** 已经以 suffix 结尾就原样返回，否则补上 */
    private static String ensureSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value : value + suffix;
    }

    /** 去掉末尾的 /v1（Ollama 的 /api/tags 在根路径下，不能挂在 /v1 后面） */
    private static String stripV1Suffix(String value) {
        return value.endsWith("/v1") ? value.substring(0, value.length() - 3) : value;
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
     * <p>{@code ConnectException} 与 {@code UnknownHostException} 是两类
     * 完全不同的原因（前者端口没开、后者域名不存在），但异常消息常常一样
     * 晦涩，这里分开表述。
     */
    private static String describeNetworkError(Throwable cause) {
        if (cause instanceof java.net.UnknownHostException) {
            return "域名解析失败：请检查端点地址拼写或本机 DNS";
        }
        if (cause instanceof java.net.ConnectException) {
            return "连接被拒绝：该端口可能没有服务在监听（Ollama 未启动？）";
        }
        if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
            return "连接超时：端点不可达或网络受限";
        }
        if (cause instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败：该端点可能使用自签证书，或地址应写成 http://";
        }
        // 走了代理/隧道的环境（公司网络、沙箱、容器）失败时是 IOException，
        // 消息形如 "Tunnel failed, got: 503"。原样透给用户等于没说 ——
        // 用户看不出是「我这边的代理挂了」还是「端点挂了」。
        if (cause instanceof java.io.IOException) {
            String raw = cause.getMessage() == null ? "" : cause.getMessage();
            if (raw.toLowerCase(Locale.ROOT).contains("tunnel")) {
                return "经由代理/隧道的连接失败：本机的代理（或沙箱网络）没能转发请求，"
                        + "请检查代理设置后重试";
            }
            return "网络连接失败：" + abbreviate(raw.isEmpty() ? cause.getClass().getSimpleName() : raw);
        }
        String message = cause.getMessage();
        String simple = cause.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return simple;
        }
        return simple + "：" + abbreviate(message);
    }

    /** 截断过长的底层异常消息，避免整屏都是堆栈式文本 */
    private static String abbreviate(String text) {
        return text.length() > 200 ? text.substring(0, 200) + "…" : text;
    }

    /**
     * 供调用方判断「这套配置能不能探测」。
     *
     * <p>抽出来是为了让 Controller 与前端提示共用同一套判断，
     * 避免两边条件写得不一样。
     */
    public static Optional<String> blockedReason(ModelProtocol protocol, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return Optional.of("请先填写端点地址");
        }
        if (protocol != ModelProtocol.OLLAMA && (apiKey == null || apiKey.isBlank())) {
            return Optional.of("请先填写 API Key");
        }
        return Optional.empty();
    }

    /** 小写化的协议名，用于日志与错误提示 */
    static String displayName(ModelProtocol protocol) {
        return protocol.name().toLowerCase(Locale.ROOT);
    }
}
