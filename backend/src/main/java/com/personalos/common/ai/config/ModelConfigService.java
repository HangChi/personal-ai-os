package com.personalos.common.ai.config;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型配置的读取、保存与「环境变量兜底」合并。
 *
 * <h2>三层取值优先级</h2>
 * <ol>
 *   <li><b>数据库</b>（设置页保存的值）—— 最高</li>
 *   <li><b>环境变量 / application.yml</b>（{@code OPENAI_*} 系列）</li>
 *   <li><b>内置默认</b>（见本类的 {@code DEFAULT_*} 常量）</li>
 * </ol>
 * 逐字段独立回落，而不是「有数据库行就整份用数据库的」。这一点很关键：
 * 用户在设置页只改了模型名，Base URL 与密钥仍应继续用环境变量里那套，
 * 否则一次局部修改会把整份配置打成空白。
 *
 * <h2>为什么把「有效地」计算放在这里而不是实体里</h2>
 * 实体只表达「用户设过什么」（全 null 合法），而「最终用哪个端点」是
 * 跨越数据库与环境变量的决策。把两层混进实体会让实体依赖外部配置，
 * 也让「未设置」与「设置为环境变量里的同一个值」无法区分。
 */
@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);

    /** 内置默认端点：与 application.yml 的 {@code spring.ai.openai.base-url} 默认值一致 */
    static final String DEFAULT_BASE_URL = "https://api.openai.com";

    /**
     * 内置默认模型名。
     *
     * <p>与 {@code application.yml} 的 {@code OPENAI_CHAT_MODEL} 默认值刻意保持一致
     * （{@code gpt-4o-mini}）：两者不一致时，用户会看到「页面上显示 A、实际请求用 B」
     * 而找不到原因。
     */
    static final String DEFAULT_CHAT_MODEL = "gpt-4o-mini";

    /** 内置默认嵌入模型名 */
    static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";

    private final AiModelConfigRepository repository;
    private final OpenAiChatModelFactory modelFactory;
    private final AiChatClientProvider provider;
    private final Clock clock;

    /** 环境变量来源的兜底值，由 Spring 在构造期解析 */
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envChatModel;
    private final String envEmbeddingModel;
    private final Double envTemperature;

    /**
     * 密钥掩码时保留的尾字符数。
     *
     * <p>保留 4 位是为了让用户能确认「这就是我上次填的那把」——
     * 同时 4 位对 32+ 位的密钥而言泄露的信息可忽略。
     */
    private static final int MASK_TAIL = 4;

    public ModelConfigService(AiModelConfigRepository repository,
                              OpenAiChatModelFactory modelFactory,
                              AiChatClientProvider provider,
                              Clock clock,
                              @Value("${spring.ai.openai.base-url:}") String envBaseUrl,
                              @Value("${spring.ai.openai.api-key:}") String envApiKey,
                              @Value("${spring.ai.openai.chat.model:}") String envChatModel,
                              @Value("${spring.ai.openai.embedding.model:}") String envEmbeddingModel,
                              @Value("${spring.ai.openai.chat.temperature:}") String envTemperature) {
        this.repository = repository;
        this.modelFactory = modelFactory;
        this.provider = provider;
        this.clock = clock;
        this.envBaseUrl = blankToNull(envBaseUrl);
        this.envApiKey = blankToNull(envApiKey);
        this.envChatModel = blankToNull(envChatModel);
        this.envEmbeddingModel = blankToNull(envEmbeddingModel);
        this.envTemperature = parseTemperature(envTemperature);
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 当前**实际生效**的配置（已合并数据库与环境变量）。
     *
     * @return 生效配置，密钥为明文 —— 调用方若是接口层，必须自行掩码
     */
    @Transactional(readOnly = true)
    public ResolvedConfig resolve() {
        AiModelConfigEntity entity = repository.findSingleton().orElse(null);
        return resolveFromLegacy(entity);
    }

    /**
     * 把一套**新表**（V21 多配置）里的配置与环境变量合并成生效配置。
     *
     * @param profile 某一套配置；{@code null} 等价于「没有任何配置」
     * @return 生效配置（密钥为明文，接口层必须掩码）
     */
    public ResolvedConfig resolveFrom(AiModelProfileEntity profile) {
        return merge(profile == null ? Values.EMPTY : Values.fromProfile(profile));
    }

    /**
     * 把**旧单行表**（V20）里的配置与环境变量合并成生效配置。
     *
     * <p>保留这条路径是为了兼容：V21 迁移会把旧行搬进新表，但万一搬迁
     * 因故没执行（例如在已被手工改过的库上升级），用户的配置不该凭空消失。
     *
     * @param entity 旧表的单行；{@code null} 表示从未保存过
     */
    public ResolvedConfig resolveFromLegacy(AiModelConfigEntity entity) {
        return merge(entity == null ? Values.EMPTY : Values.fromLegacy(entity));
    }

    /**
     * 把配置值与环境变量合并成生效配置。
     *
     * <p>逐字段回落：配置里的列非 null 优先，否则用环境变量，最后用内置默认。
     *
     * <p>入参是 {@link Values} 而不是某个实体：V20 单行表与 V21 多行表
     * 字段完全一样，只是身份不同（单行 vs 多行）。为它们各写一份 merge
     * 会让「三层回落」这套核心规则出现两份可能走偏的实现 ——
     * 抽成值对象后只有一份。
     */
    private ResolvedConfig merge(Values values) {
        String baseUrl = firstNonBlank(values.baseUrl(), envBaseUrl, DEFAULT_BASE_URL);
        String apiKey = firstNonBlank(values.apiKey(), envApiKey, "");
        String chatModel = firstNonBlank(values.chatModel(), envChatModel, DEFAULT_CHAT_MODEL);
        String embeddingModel = firstNonBlank(
                values.embeddingModel(), envEmbeddingModel, DEFAULT_EMBEDDING_MODEL);
        Double temperature = values.temperature() != null ? values.temperature() : envTemperature;

        // 每一项都记下「来源」，前端据此显示「来自环境变量」还是「设置页覆盖」。
        // 没有这个信息，用户改了 .env 发现不生效时无从判断是被覆盖了。
        Map<String, ConfigSource> sources = new LinkedHashMap<>();
        sources.put("baseUrl", sourceOf(values.baseUrl(), envBaseUrl));
        sources.put("apiKey", sourceOf(values.apiKey(), envApiKey));
        sources.put("chatModel", sourceOf(values.chatModel(), envChatModel));
        sources.put("embeddingModel", sourceOf(values.embeddingModel(), envEmbeddingModel));
        sources.put("temperature", values.temperature() != null
                ? ConfigSource.DATABASE
                : (envTemperature != null ? ConfigSource.ENVIRONMENT : ConfigSource.DEFAULT));

        return new ResolvedConfig(baseUrl, apiKey, chatModel, temperature, embeddingModel,
                values.revision(), values.updatedAt(), sources);
    }

    // ------------------------------------------------------------------ 写入

    /**
     * 保存配置到数据库。**只落库，不切换模型** —— 切换由调用方在本事务外做。
     *
     * <h3>为什么落库与切换必须分开</h3>
     * 这是本轮踩到的一个真实 bug：早先这里标了 {@code @Transactional} 并在方法末尾
     * 调用 {@link #applyToRuntime}，而后者在模型构造失败时抛
     * {@link BusinessException} —— 异常穿出事务边界会让**整个事务回滚**。
     * 结果是「配置已保存，但按该配置初始化模型失败」这句提示在说谎：
     * 配置根本没保存，数据库里一行都没有。
     *
     * <p>正确语义是「保存成功」与「切换成功」两件事分开报告：本方法只负责前者
     * （几乎不会失败），后者交给 {@link ModelConfigController#save} 在事务外调用
     * {@link #applyToRuntime}。切换失败时配置仍然保留，用户改个参数重试即可，
     * 不必重新填一遍。
     *
     * <p><b>不要把这个方法改回「保存 + 生效」一步到位。</b>那会把两件失败概率
     * 完全不同的事绑到同一个事务边界上，回滚时还会让提示语与真实状态不一致。
     *
     * @param command 用户提交的表单
     * @return 保存并合并后的生效配置（密钥为明文，接口层必须掩码）
     */
    @Transactional
    public ResolvedConfig save(SaveCommand command) {
        Instant now = clock.instant();
        AiModelConfigEntity entity = repository.findSingleton()
                .orElseGet(() -> new AiModelConfigEntity(now));

        // 密钥的特殊约定：传 null 表示「不改动」，传空串表示「清空、回落到环境变量」。
        // 前端不回填明文，因此「用户没碰密钥输入框」与「用户清空了密钥」必须能区分，
        // 否则每次保存其他字段都会把密钥清掉。
        String apiKey = command.apiKey() == null
                ? (entity.getApiKey())
                : blankToNull(command.apiKey());

        entity.overwrite(
                blankToNull(command.baseUrl()),
                apiKey,
                blankToNull(command.chatModel()),
                command.temperature(),
                blankToNull(command.embeddingModel()),
                now);
        repository.save(entity);

        ResolvedConfig resolved = merge(Values.fromLegacy(entity));
        log.info("模型配置已保存：revision={}, baseUrl={}, chatModel={}, 密钥{}",
                entity.getRevision(), resolved.baseUrl(), resolved.chatModel(),
                resolved.hasApiKey() ? "已设置" : "为空");
        return resolved;
    }

    /**
     * 按生效配置重建聊天模型；失败时抛出可读的业务异常。
     *
     * <p>不吞异常：调用方需要知道「配置存下来了，但这套参数连不上」。
     *
     * <p><b>必须在事务外调用。</b>抛出的 {@link BusinessException} 若发生在事务内
     * 会让事务整体回滚，连带把已经落库的配置一起撤销 —— 见 {@link #save} 的说明。
     */
    public void applyToRuntime(ResolvedConfig resolved) {
        applyResolved(resolved);
    }

    /**
     * 与 {@link #applyToRuntime} 同义。
     *
     * <p>多配置那边（{@link ModelProfileService#applyCurrent}）用的名字。
     * 保留两个别名而不是重命名：{@code applyToRuntime} 已被既有测试与
     * 控制器调用，改名会制造一次无意义的改动面。
     */
    public void applyResolved(ResolvedConfig resolved) {
        if (resolved.apiKey().isBlank()) {
            // 没有密钥时不去构造模型 —— 构造本身不校验密钥，但任何请求都会失败。
            // 保持现有模型不动（若环境变量那套仍可用，就不该因为设置页留空而失能）。
            log.info("生效配置中没有 API 密钥，聊天模型保持不变");
            return;
        }
        try {
            var model = modelFactory.create(
                    resolved.baseUrl(), resolved.apiKey(), resolved.chatModel(),
                    resolved.temperature(), Map.of());
            provider.replaceModel(model);
        } catch (RuntimeException cause) {
            throw new BusinessException(ErrorCode.AI_UPSTREAM_ERROR,
                    "配置已保存，但按该配置初始化模型失败：" + describe(cause));
        }
    }

    /**
     * 启动时把数据库里的配置应用到运行期。
     *
     * <p>没有数据库行、或当时没有密钥时什么都不做 —— 启动期由 Spring AI 自动配置
     * 装配的模型继续生效，行为与改造前完全一致。
     */
    public void applyOnStartup() {
        ResolvedConfig resolved = resolve();
        if (resolved.revision() == null) {
            log.info("模型配置来自环境变量（数据库无覆盖）：baseUrl={}, chatModel={}",
                    resolved.baseUrl(), resolved.chatModel());
            return;
        }
        if (resolved.apiKey().isBlank()) {
            log.info("数据库有模型配置但没有密钥，沿用启动期模型");
            return;
        }
        try {
            provider.replaceModel(modelFactory.create(
                    resolved.baseUrl(), resolved.apiKey(), resolved.chatModel(),
                    resolved.temperature(), Map.of()));
            log.info("已应用数据库中的模型配置：revision={}, baseUrl={}, chatModel={}",
                    resolved.revision(), resolved.baseUrl(), resolved.chatModel());
        } catch (RuntimeException cause) {
            // 启动期不能因为配置有问题就起不来：那是「一个可修的配置错误」被升级成
            // 「整个应用不可用」。记录后继续，用户可在设置页改回来。
            log.warn("应用数据库中的模型配置失败，沿用启动期模型：{}", describe(cause));
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 掩码密钥：保留末 4 位。
     *
     * @param apiKey 明文密钥
     * @return 形如 {@code sk-****...8f3a} 的掩码；空密钥返回空串
     */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        if (apiKey.length() <= MASK_TAIL) {
            // 极短密钥（不该出现）整体打码，不暴露任何字符
            return "****";
        }
        return "****" + apiKey.substring(apiKey.length() - MASK_TAIL);
    }

    private static ConfigSource sourceOf(String dbValue, String envValue) {
        if (dbValue != null && !dbValue.isBlank()) {
            return ConfigSource.DATABASE;
        }
        return envValue != null ? ConfigSource.ENVIRONMENT : ConfigSource.DEFAULT;
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static Double parseTemperature(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.strip());
        } catch (NumberFormatException ignored) {
            // 配置里的温度写错不该阻断启动，回落「未设置」
            return null;
        }
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank()
                ? cause.getClass().getSimpleName()
                : message;
    }

    // ------------------------------------------------------------------ 类型

    /**
     * 一份配置的**值**（不含身份）。
     *
     * <p>V20 单行表与 V21 多行表的配置字段完全一致，差的只是「一行还是多行」。
     * 三层回落是同一套规则，不该为两种实体各写一份 —— 那样改一处漏一处时
     * 只会在某条路径上静默用错默认值。这里抽成最小值对象，
     * {@link #merge} 只看它。
     *
     * @param baseUrl        端点地址；null 表示未覆盖
     * @param apiKey         密钥明文；null 表示未覆盖
     * @param chatModel      对话模型名；null 表示未覆盖
     * @param temperature    温度；null 表示未覆盖
     * @param embeddingModel 嵌入模型名；null 表示未覆盖
     * @param revision       配置版本；null 表示「从未保存过」
     * @param updatedAt      最后保存时间；null 表示「从未保存过」
     */
    record Values(String baseUrl,
                  String apiKey,
                  String chatModel,
                  Double temperature,
                  String embeddingModel,
                  Integer revision,
                  Instant updatedAt) {

        /** 「没有任何配置」—— 全部回落到环境变量与内置默认 */
        static final Values EMPTY = new Values(null, null, null, null, null, null, null);

        /**
         * 从 V20 单行表实体取值。
         *
         * <p>两个实体各一个**具名**工厂，而不是同名 {@code of} 重载：
         * 调用点的 `entity == null ? EMPTY : of(entity)` 里 {@code null} 在
         * 两个重载间是二义的，编译直接失败。具名方法没有这个问题，
         * 而且读起来就写明「这份值来自哪张表」。
         */
        static Values fromLegacy(AiModelConfigEntity entity) {
            return new Values(entity.getBaseUrl(), entity.getApiKey(), entity.getChatModel(),
                    entity.getTemperature(), entity.getEmbeddingModel(),
                    entity.getRevision(), entity.getUpdatedAt());
        }

        /** 从 V21 多配置实体取值 */
        static Values fromProfile(AiModelProfileEntity entity) {
            return new Values(entity.getBaseUrl(), entity.getApiKey(), entity.getChatModel(),
                    entity.getTemperature(), entity.getEmbeddingModel(),
                    entity.getRevision(), entity.getUpdatedAt());
        }
    }

    /** 配置项的来源，用于前端区分「设置页覆盖」与「环境变量兜底」 */
    public enum ConfigSource {
        /** 设置页保存的值 */
        DATABASE,
        /** 来自环境变量 / application.yml */
        ENVIRONMENT,
        /** 代码内置默认 */
        DEFAULT
    }

    /**
     * 生效配置（数据库 + 环境变量合并后的结果）。
     *
     * @param baseUrl        OpenAI 兼容端点根地址
     * @param apiKey         密钥明文；**接口层必须掩码后再返回**
     * @param chatModel      对话模型名
     * @param temperature    采样温度；null 表示沿用服务端默认
     * @param embeddingModel 嵌入模型名（改动需重启）
     * @param revision       数据库配置版本；null 表示数据库里没有配置行
     * @param updatedAt      最后一次保存时间
     * @param sources        各字段的来源
     */
    public record ResolvedConfig(String baseUrl,
                                 String apiKey,
                                 String chatModel,
                                 Double temperature,
                                 String embeddingModel,
                                 Integer revision,
                                 Instant updatedAt,
                                 Map<String, ConfigSource> sources) {

        /** 当前是否配了密钥（不暴露密钥本身） */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * 保存指令。
     *
     * @param baseUrl        端点地址；空表示回落到环境变量
     * @param apiKey         密钥；**null 表示不改动**，空串表示清空
     * @param chatModel      模型名；空表示回落到环境变量
     * @param temperature    温度；null 表示回落到环境变量
     * @param embeddingModel 嵌入模型；空表示回落到环境变量（需重启生效）
     */
    public record SaveCommand(String baseUrl,
                              String apiKey,
                              String chatModel,
                              Double temperature,
                              String embeddingModel) {
    }
}
