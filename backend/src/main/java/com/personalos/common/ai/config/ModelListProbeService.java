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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * 补查单模型详情的最多请求数。
     *
     * <p>列表接口不返回窗口时必须逐个问 {@code /models/{name}}，但一个端点
     * 可能列出 183 个模型（DashScope 实测 183），逐个问就是 183 次往返。
     *
     * <p><b>取 12 个而不是 40 个</b>：实测 DashScope 有严格的 QPS 限制 ——
     * 8 个并发请求里有 3 个立刻返回 429（「You have exceeded your current
     * request limit」）。名额越多，被拒的比例越高，而名额本身就是
     * <b>按优先级</b>分配的（见 {@code orderedDetailTargets}），
     * 排在后面的本来就是用户不关心的模型，多查只是白撞限流。
     *
     * <p>12 个覆盖「用户当前清单里的几个模型 + 少量兜底」绰绰有余：
     * 用户清单通常 2~6 个，加 6~10 个兜底已经够用。
     */
    private static final int MAX_DETAIL_LOOKUPS = 12;

    /**
     * 优先补查名单里最多看几个（V26）。
     *
     * <p>用户当前配置清单通常只有 2~6 个模型，给
     * {@value #MAX_DETAIL_LOOKUPS} 个名额已完全够用；上限与总名额相同，
     * 因为「优先名单」本就该优先吃满所有名额 —— 它才是用户真正关心的。
     */
    private static final int MAX_PREFERRED_LOOKUPS = MAX_DETAIL_LOOKUPS;

    /**
     * 补查详情的总时间预算（毫秒）。
     *
     * <p>前端超时 15 秒、本服务定时 10 秒。补查是**尽力而为**的增强步骤：
     * 用完预算就带着已有结果返回，绝不让整个探测失败。
     * 取 6000ms 留足余量给最后的序列化与网络抖动。
     *
     * <p>12 个模型 × 单次 ~600ms ÷ 4 路并发 ≈ 1.8 秒，预算非常充裕；
     * 留这么大余量是为了容忍个别慢请求，而不是为了塞更多名额。
     */
    private static final long DETAIL_BUDGET_MS = 6_000;

    /** 单个详情请求的超时：拿不到就算了，不能占着预算空等 */
    private static final long DETAIL_TIMEOUT_MS = 2_500;

    /**
     * 补查详情的并发度。
     *
     * <h3>为什么是 4 而不是 8（实测下调）</h3>
     * 2026-10-28 对 DashScope 直接压测 8 个并发详情请求：
     * <pre>
     *   deepseek-v4.1-flash-1017 → 429
     *   deepseek-v4-pro          → 429
     *   cmaas-qwen3.8-flash      → 429
     *   （其余 5 个 → 200）
     * </pre>
     * 8 路并发下 3/8 被限流。而**最坏的后果不是慢，是「用户最想查的模型
     * 恰好被拒」** —— 排在最前的优先名单撞上第一波请求，最容易吃 429。
     *
     * <p>并发 4 时实测无 429，12 个模型 3 轮往返约 1.8 秒，完全在预算内。
     * 压低并发换来的「每个请求都真的成功」远比「发出更多请求」有价值。
     */
    private static final int DETAIL_PARALLELISM = 4;

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
        return probe(protocol, baseUrl, apiKey, List.of());
    }

    /**
     * 探测模型列表，并优先给指定模型补查上下文窗口。
     *
     * <p>{@code preferredModels} 是用户当前配置里已选中的模型名。它们排在
     * 补查队列最前面 —— 大端点上按字母序截断会把真正在用的模型全部漏掉
     * （DashScope：{@code qwen3.8-flash} 第 148 位）。
     *
     * @param preferredModels 优先补查的模型名；可空
     */
    public ModelListResult probe(ModelProtocol protocol, String baseUrl, String apiKey,
                                 List<String> preferredModels) {
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
            return interpret(effective, normalized, uri, request, response, latencyMs, preferredModels);
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
    private ModelListResult interpret(ModelProtocol protocol, String baseUrl, URI uri,
                                      HttpRequest listRequest, HttpResponse<String> response,
                                      long latencyMs, List<String> preferredModels) {
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
        JsonNode array;
        String nameField;
        if (protocol == ModelProtocol.OLLAMA) {
            array = root.path("models");
            nameField = "name";
            collectIds(array, nameField, ids);
            // 兜底：某些版本/反代在 /api/tags 也吐 OpenAI 形状
            if (ids.isEmpty()) {
                array = root.path("data");
                nameField = "id";
                collectIds(array, nameField, ids);
            }
        } else {
            array = root.path("data");
            nameField = "id";
            collectIds(array, nameField, ids);
            if (ids.isEmpty()) {
                // 少数中转站直接返回数组，或把列表放在 models 下
                if (root.isArray()) {
                    array = root;
                    collectIds(array, nameField, ids);
                } else {
                    array = root.path("models");
                    collectIds(array, nameField, ids);
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
        Map<String, Integer> windows = collectContextWindows(array, nameField, sorted);
        // 列表里一个窗口都没自报 → 逐个问详情。这是绝大多数端点的真实情况：
        // 原生 OpenAI / Anthropic 的列表完全不返回窗口，DashScope 则把它藏在
        // /models/{name} 的 extra_info 里（实测 183 个模型全部如此）。
        if (windows.isEmpty() && protocol != ModelProtocol.OLLAMA) {
            windows = lookupDetailWindows(protocol, baseUrl, listRequest, sorted, preferredModels);
        }
        log.info("模型列表探测成功：{} {} → {} 个模型（其中 {} 个自报上下文窗口），耗时 {}ms",
                protocol, uri, sorted.size(), windows.size(), latencyMs);
        return new ModelListResult(true, sorted, latencyMs, null, null, windows);
    }

    /**
     * 逐个请求 {@code /models/{name}} 补出上下文窗口（V26）。
     *
     * <h2>为什么必须做这一步</h2>
     * 列表接口基本不返回窗口，而被用户当成「拿不到窗口」的那些端点里，
     * 至少有一类（DashScope）把真实值放在单模型详情的
     * {@code extra_info.default_envs.max_input_tokens} 上。实测：
     *
     * <pre>
     *   GET /compatible-mode/v1/models            → 183 个模型，0 个带窗口
     *   GET /compatible-mode/v1/models/qwen-max   → extra_info.default_envs.max_input_tokens = 30720
     * </pre>
     *
     * <p>不补这一步，「自动识别窗口」对这类端点**完全无效**，用户只能手填。
     *
     * <h2>为什么必须并发、且必须截断</h2>
     * 实测（2026-10-28，DashScope 183 个模型全量）：
     * <pre>
     *   并发  8：13.87s，取到 49 个
     *   并发 16：14.50s，取到 39 个
     *   并发 32： 7.88s，取到 24 个
     * </pre>
     * 并发越高反而**取到越少** —— 这是限流（429）的确凿证据，不是网络抖动。
     * 所以并发度压到 {@value #DETAIL_PARALLELISM}，名额硬卡
     * {@value #MAX_DETAIL_LOOKUPS}，总预算 {@value #DETAIL_BUDGET_MS}ms。
     *
     * <h2>为什么名额必须由「优先名单」先占（V26 修的关键 bug）</h2>
     * 原实现直接 {@code models.stream().limit(40)} —— 而 {@code models}
     * 是**按字母序**排好的。DashScope 的 183 个模型里：
     * <pre>
     *   qwen3.8-flash            第 148 位  ← 用户实际在用的主模型
     *   qwen-max                 第  65 位  ← 用户实际在用的备用
     *   deepseek-v4.1-flash-1017 第   9 位  ← 恰好挤进 40 内，所以看起来"有时能用"
     * </pre>
     * 于是「有时能识别、有时不能」—— 这不是概率，而是模型名首字母决定的。
     * 现在改成：用户清单里的模型先查，剩下的名额才按字母序兜底。
     *
     * <h2>为什么撞到 429 要立刻收手</h2>
     * 名额再少也可能踩到限流。**继续发只会让剩余请求全部被拒**，
     * 而每个被拒的请求仍占一次额度、白白烧掉时间预算。所以一旦观察到
     * 限流，就让所有 worker 停下 —— 已有的结果照样返回。
     *
     * <h2>为什么失败全部静默</h2>
     * 补查是「锦上添花」：拿不到就回落到内置表与用户手填，不影响探测本身
     * 已经成功这个事实。任何单个模型的失败都不该让整次探测变成报错。
     *
     * @param listRequest     原列表请求：直接复用它证明过的鉴权头与协议细节，
     *                        避免为三种协议再写一遍构造逻辑（也避免两边走偏）
     * @param preferredModels 用户已选中的模型：优先补查，排在队列最前
     */
    private Map<String, Integer> lookupDetailWindows(ModelProtocol protocol, String baseUrl,
                                                     HttpRequest listRequest, List<String> models,
                                                     List<String> preferredModels) {
        List<String> targets = orderedDetailTargets(models, preferredModels);
        if (targets.isEmpty()) {
            return Map.of();
        }
        String root = stripTrailingSlash(baseUrl.trim());
        // 两种协议的详情路径都是 /v1/models/{name}；写成常量而不是三元，
        // 免得后来人误以为两家路径不同（列表接口是一样的：/v1/models）
        String path = "/v1/models/";
        // 用户填的 baseUrl 已含 /v1 时不能再补一次
        String base = root.endsWith("/v1") ? root.substring(0, root.length() - 3) : root;

        Map<String, Integer> found = new java.util.concurrent.ConcurrentHashMap<>();
        long deadline = System.nanoTime() + Duration.ofMillis(DETAIL_BUDGET_MS).toNanos();
        // 一旦任一线程撞上限流就置位，所有线程见到它就收手
        java.util.concurrent.atomic.AtomicBoolean throttled = new java.util.concurrent.atomic.AtomicBoolean();

        java.util.concurrent.atomic.AtomicInteger cursor = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < Math.min(DETAIL_PARALLELISM, targets.size()); i++) {
            Thread worker = Thread.ofVirtual().name("model-detail-" + i).unstarted(() -> {
                while (true) {
                    int index = cursor.getAndIncrement();
                    if (index >= targets.size() || System.nanoTime() > deadline || throttled.get()) {
                        return;
                    }
                    String name = targets.get(index);
                    DetailOutcome outcome = fetchDetailWindow(base, path, name, listRequest);
                    if (outcome.window() != null) {
                        found.put(name, outcome.window());
                    } else if (outcome.rateLimited()) {
                        // 只在本轮确实收到过 429 时才停：普通 404/500 仍可继续
                        throttled.set(true);
                        log.warn("补查详情被限流（429），已停止后续请求，仅保留已拿到的 {} 个窗口", found.size());
                        return;
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread worker : workers) {
            try {
                worker.join(Math.max(1, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())).toMillis()));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!found.isEmpty()) {
            log.info("补查单模型详情拿到 {} 个上下文窗口（共尝试 {} 个模型）", found.size(), targets.size());
        }
        return found;
    }

    /**
     * 决定补查顺序：优先名单在前，其余按列表原序兜底，总长不超上限。
     *
     * <p>抽成静态包级方法是为了**能单测** —— 这是本轮 bug 的所在，
     * 光测「能取到窗口」无法区分「取到的是用户要的」还是「取到的是字母序前几个」。
     *
     * <p>三条规则：
     * <ol>
     *   <li>优先名单里<b>且确实存在于该端点列表</b>的才算 —— 否则会拿一个
     *       该端点根本没有的模型名去发请求，白耗预算；</li>
     *   <li>优先名单最多取 {@value #MAX_PREFERRED_LOOKUPS} 个，防畸形请求；</li>
     *   <li>余下名额按<b>列表原序</b>补满，保证「用户还没选模型时」仍有兜底。</li>
     * </ol>
     */
    static List<String> orderedDetailTargets(List<String> models, List<String> preferredModels) {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        Set<String> available = new HashSet<>(models);
        List<String> ordered = new ArrayList<>();

        if (preferredModels != null) {
            preferredModels.stream()
                    .filter(name -> name != null && available.contains(name))
                    .distinct()
                    .limit(MAX_PREFERRED_LOOKUPS)
                    .forEach(ordered::add);
        }
        for (String name : models) {
            if (ordered.size() >= MAX_DETAIL_LOOKUPS) {
                break;
            }
            if (!ordered.contains(name)) {
                ordered.add(name);
            }
        }
        return ordered.size() > MAX_DETAIL_LOOKUPS ? ordered.subList(0, MAX_DETAIL_LOOKUPS) : ordered;
    }

    /**
     * 单个详情请求的结果。
     *
     * <p>把「限流」从「普通失败」里分出来，是因为两者的正确应对**完全相反**：
     * 普通失败（404/500）说明这个模型没有详情接口，继续查下一个就好；
     * 而 429 说明整条链路已经在被拒，继续发只会让后面的请求全部失败，
     * 必须立刻收手保住已有结果。
     *
     * @param window      取到的窗口；没取到为 null
     * @param rateLimited 本轮是否被限流（429）
     */
    private record DetailOutcome(Integer window, boolean rateLimited) {

        static DetailOutcome found(int window) {
            return new DetailOutcome(window, false);
        }

        static DetailOutcome missing() {
            return new DetailOutcome(null, false);
        }

        static DetailOutcome throttled() {
            return new DetailOutcome(null, true);
        }
    }

    /**
     * 问一个模型的详情，取出它自报的窗口。
     *
     * <p>模型名可能带路径分隔符（{@code kimi/kimi-k3.1-pro}），逐段编码但
     * **保留斜杠**：整段 {@code URLEncoder.encode} 会把 {@code /} 编成
     * {@code %2F}，多数网关不认，直接 404。
     */
    private DetailOutcome fetchDetailWindow(String base, String path, String modelName,
                                            HttpRequest listRequest) {
        try {
            String encoded = java.util.Arrays.stream(modelName.split("/"))
                    .map(part -> java.net.URLEncoder.encode(part, StandardCharsets.UTF_8))
                    .reduce((a, b) -> a + "/" + b)
                    .orElse("");
            URI detailUri = URI.create(base + path + encoded);
            // 复用列表请求的头：鉴权方式（Bearer / x-api-key）与版本头都已验证可用
            HttpRequest detailRequest = HttpRequest.newBuilder(detailUri)
                    .GET()
                    .timeout(Duration.ofMillis(DETAIL_TIMEOUT_MS))
                    .headers(listRequest.headers().map().entrySet().stream()
                            .filter(e -> !e.getKey().equalsIgnoreCase("host")
                                    && !e.getKey().equalsIgnoreCase("content-length"))
                            .flatMap(e -> e.getValue().stream().flatMap(v -> java.util.stream.Stream.of(e.getKey(), v)))
                            .toArray(String[]::new))
                    .build();
            HttpResponse<String> response = httpClient.send(detailRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 429) {
                return DetailOutcome.throttled();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return DetailOutcome.missing();
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || root.isNull()) {
                return DetailOutcome.missing();
            }
            Integer window = readWindow(root);
            return window == null ? DetailOutcome.missing() : DetailOutcome.found(window);
        } catch (IOException | RuntimeException ex) {
            // 单模型的失败不值得记日志：多数模型并不支持详情接口，
            // 全记下来会把日志刷满，反而盖住真正的问题
            return DetailOutcome.missing();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DetailOutcome.missing();
        }
    }

    /**
     * 从模型列表里提取自报的上下文窗口（V25）。
     *
     * <h3>为什么字段名要试这么多个</h3>
     * 没有标准。各家各写各的，实测与文档里见过的至少有五种：
     * <ul>
     *   <li>{@code context_length} —— OpenAI 兼容中转、部分 vLLM 部署</li>
     *   <li>{@code context_window} —— 部分聚合网关</li>
     *   <li>{@code max_model_len} —— vLLM 原生</li>
     *   <li>{@code max_context_length} / {@code max_input_tokens} —— 少数中转站</li>
     *   <li>Ollama 的 {@code model_info.*.context_length} —— 嵌套在元信息里</li>
     * </ul>
     * 原生 OpenAI 与 Anthropic 的 {@code /models} **都不返回**这个字段，
     * 所以「一个都没解析到」是常态，不是异常 —— 此时返回空表，
     * 由内置对照表与用户手填兜底。
     *
     * <p>只收录通过下限校验的值：把 0、负数、明显是字符数（如 100000000）
     * 之类的东西写进界面，比留空更糟 —— 用户会照着它去调预算。
     */
    private static Map<String, Integer> collectContextWindows(JsonNode array, String nameField,
                                                              List<String> wanted) {
        if (array == null || !array.isArray() || wanted.isEmpty()) {
            return Map.of();
        }
        Set<String> keep = new HashSet<>(wanted);
        Map<String, Integer> out = new HashMap<>();
        for (JsonNode node : array) {
            JsonNode nameNode = node.path(nameField);
            if (!nameNode.isString()) {
                continue;
            }
            String name = nameNode.asString();
            if (!keep.contains(name)) {
                continue;
            }
            Integer window = readWindow(node);
            if (window != null) {
                out.put(name, window);
            }
        }
        return out;
    }

    /** 按上述多种字段名依次尝试读一个合理的窗口值；都不像就返回 null */
    private static Integer readWindow(JsonNode node) {
        for (String field : new String[] {
                "context_length", "context_window", "max_model_len",
                "max_context_length", "max_input_tokens"}) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                int window = value.asInt();
                if (isPlausibleWindow(window)) {
                    return window;
                }
            }
        }
        // Ollama /api/tags 把它塞在 model_info 下，键名形如 "llama.context_length"
        JsonNode info = node.path("model_info");
        if (info.isObject()) {
            java.util.Iterator<Map.Entry<String, JsonNode>> it = info.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                if (entry.getKey().endsWith(".context_length") && entry.getValue().isNumber()) {
                    int window = entry.getValue().asInt();
                    if (isPlausibleWindow(window)) {
                        return window;
                    }
                }
            }
        }
        Integer nested = readNestedWindow(node);
        if (nested != null) {
            return nested;
        }
        return null;
    }

    /**
     * 在嵌套容器里找窗口（V26）。
     *
     * <h2>为什么必须递归找</h2>
     * DashScope 的单模型详情把它放在
     * {@code extra_info.default_envs.max_input_tokens} —— 两层嵌套，且
     * <b>三个已知的窗口字段名都出现在这里</b>。只查顶层字段的实现
     * 对这类端点恒返回 null，于是「自动识别」看起来完全失效。
     *
     * <pre>
     *   GET /compatible-mode/v1/models/qwen-max
     *   { "id": "qwen-max",
     *     "extra_info": { "default_envs": { "max_input_tokens": 30720 } } }
     * </pre>
     *
     * <p>深度限制 4 层：真实响应不会更深，而无限递归会在
     * 异常端点上把栈打爆。只往 object 与 array 里走，标量不递归。
     *
     * <p>先查 {@code extra_info} 再查 {@code data}：前者是详情接口的形状，
     * 后者是少数聚合网关把详情又包一层的形状。
     */
    private static Integer readNestedWindow(JsonNode node) {
        for (String container : new String[] {"extra_info", "data", "model_info", "meta", "metadata"}) {
            JsonNode child = node.path(container);
            if (child.isObject()) {
                Integer found = findWindowInObject(child, 0);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 在 object 内部广度优先找窗口字段。
     *
     * <p>用显式队列而不是递归：深度与节点数都不可控时，队列天然有上限，
     * 且能让「先浅后深」的顺序成立 —— 越靠近顶层越可能是真正的窗口声明，
     * 深处的同名键更可能是别的含义（如某个 tokenizer 配置里的长度）。
     */
    private static Integer findWindowInObject(JsonNode root, int startDepth) {
        Set<String> fields = Set.of("max_input_tokens", "context_length", "context_window",
                "max_model_len", "max_context_length");
        java.util.ArrayDeque<Map.Entry<JsonNode, Integer>> queue = new java.util.ArrayDeque<>();
        queue.add(Map.entry(root, startDepth));
        while (!queue.isEmpty()) {
            Map.Entry<JsonNode, Integer> current = queue.poll();
            JsonNode node = current.getKey();
            int depth = current.getValue();
            if (depth > 4) {
                continue;
            }
            if (node.isObject()) {
                java.util.Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    JsonNode value = entry.getValue();
                    if (fields.contains(entry.getKey()) && value.isNumber()) {
                        int window = value.asInt();
                        if (isPlausibleWindow(window)) {
                            return window;
                        }
                    }
                    if (value.isObject() || value.isArray()) {
                        queue.add(Map.entry(value, depth + 1));
                    }
                }
            } else if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isObject() || item.isArray()) {
                        queue.add(Map.entry(item, depth + 1));
                    }
                }
            }
        }
        return null;
    }

    /**
     * 窗口是否像真的：1024 ~ 32M。
     *
     * <p>下限 1024 因为再小的窗口连系统提示都放不下，不可能是「上下文窗口」；
     * 上限 32M 用来挡掉把**字符数**当 token 报的端点（1M token ≈ 160 万汉字，
     * 报成 1.6 亿就明显是字符口径）。超出范围的宁可当「未知」。
     */
    private static boolean isPlausibleWindow(int window) {
        return window >= 1024 && window <= 32 * 1024 * 1024;
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
