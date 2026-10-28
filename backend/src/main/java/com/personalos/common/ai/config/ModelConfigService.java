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
public class ModelConfigService implements com.personalos.common.ai.RuntimeChatModelResolver {

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

    /**
     * 多配置服务（V23 按模型名解析时要知道「当前生效配置」）。
     *
     * <p>用 {@code ObjectProvider} 而不是直接注入：{@code ModelProfileService}
     * 依赖本类，构造期互注会形成循环依赖。懒取让编译期依赖方向保持单向。
     */
    private final org.springframework.beans.factory.ObjectProvider<ModelProfileService>
            profileServiceProvider;

    /** 环境变量来源的兜底值，由 Spring 在构造期解析 */
    private final String envBaseUrl;
    private final String envApiKey;
    private final String envChatModel;
    private final String envEmbeddingModel;
    private final Double envTemperature;

    /**
     * 用于判断「某个值到底是不是真的来自环境变量」。
     *
     * <h3>为什么仅凭 {@code @Value} 的值判断不出来</h3>
     * {@code application.yml} 里写的是 {@code base-url: ${OPENAI_BASE_URL:https://api.openai.com}}。
     * 于是即便 {@code OPENAI_BASE_URL} 根本没设置，{@code @Value} 注入到的也是一个
     * 非空的 {@code https://api.openai.com} —— 与「用户真的设了这个变量」在
     * Java 侧完全无法区分。
     *
     * <p>后果是界面上的来源徽章把「内置默认」标成「环境变量」：用户去改
     * {@code .env} 发现没反应，而界面正告诉他「这项来自环境变量」——
     * 这个徽章存在的唯一意义就是解释「为什么我改的不生效」，标错就等于反向误导。
     *
     * <p>用 {@link Environment} 按**原始变量名**查一次，就能区分：占位符
     * {@code ${OPENAI_BASE_URL}} 的解析结果不会在属性源里留下
     * {@code OPENAI_BASE_URL} 这个键，只有真正定义了该变量的属性源
     * （系统环境变量、或 {@code .env}）才会命中。
     *
     * <p>测试用构造器传 {@code null}，此时退回「值非空即 ENVIRONMENT」的旧行为，
     * 保持既有断言语义不变。
     */
    private final org.springframework.core.env.Environment environment;

    /** 与 {@code application.yml} 中占位符同名的原始变量名 */
    private static final String KEY_BASE_URL = "OPENAI_BASE_URL";
    private static final String KEY_API_KEY = "OPENAI_API_KEY";
    private static final String KEY_CHAT_MODEL = "OPENAI_CHAT_MODEL";
    private static final String KEY_EMBEDDING_MODEL = "OPENAI_EMBEDDING_MODEL";


    /**
     * 密钥掩码时保留的尾字符数。
     *
     * <p>保留 4 位是为了让用户能确认「这就是我上次填的那把」——
     * 同时 4 位对 32+ 位的密钥而言泄露的信息可忽略。
     */
    private static final int MASK_TAIL = 4;

    /**
     * 生产构造器。
     *
     * <p>显式标 {@code @Autowired} 是必须的：本类有两个构造器（另一个是给测试的
     * 兼容重载），Spring 在没有标注时无法判断该用哪个，启动会报
     * 「No default constructor found」—— 一个看起来与「构造函数」毫不相干的报错。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelConfigService(AiModelConfigRepository repository,
                              OpenAiChatModelFactory modelFactory,
                              AiChatClientProvider provider,
                              Clock clock,
                              org.springframework.beans.factory.ObjectProvider<ModelProfileService>
                                      profileServiceProvider,
                              org.springframework.core.env.Environment environment,
                              @Value("${spring.ai.openai.base-url:}") String envBaseUrl,
                              @Value("${spring.ai.openai.api-key:}") String envApiKey,
                              @Value("${spring.ai.openai.chat.model:}") String envChatModel,
                              @Value("${spring.ai.openai.embedding.model:}") String envEmbeddingModel,
                              @Value("${spring.ai.openai.chat.temperature:}") String envTemperature) {
        this.repository = repository;
        this.modelFactory = modelFactory;
        this.provider = provider;
        this.clock = clock;
        this.profileServiceProvider = profileServiceProvider;
        this.environment = environment;
        this.envBaseUrl = blankToNull(envBaseUrl);
        this.envApiKey = blankToNull(envApiKey);
        this.envChatModel = blankToNull(envChatModel);
        this.envEmbeddingModel = blankToNull(envEmbeddingModel);
        this.envTemperature = parseTemperature(envTemperature);
    }

    /**
     * 兼容构造器：不传 {@link Environment}（子类桩、以及只关心生效值的测试用）。
     *
     * <p>此时来源判定退回「值非空即 ENVIRONMENT」的旧语义 —— 因为无法查证
     * 某个变量名是否真的被定义过。生产路径永远走上面那个带 Environment 的构造器。
     */
    public ModelConfigService(AiModelConfigRepository repository,
                              OpenAiChatModelFactory modelFactory,
                              AiChatClientProvider provider,
                              Clock clock,
                              org.springframework.beans.factory.ObjectProvider<ModelProfileService>
                                      profileServiceProvider,
                              String envBaseUrl,
                              String envApiKey,
                              String envChatModel,
                              String envEmbeddingModel,
                              String envTemperature) {
        this(repository, modelFactory, provider, clock, profileServiceProvider, null,
                envBaseUrl, envApiKey, envChatModel, envEmbeddingModel, envTemperature);
    }

    /**
     * 兼容构造器：不接多配置服务（V22 及以前的测试用）。
     *
     * <p>此时 {@link #currentResolved()} 回落到「只有单行表 + 环境变量」的解析结果，
     * 与改造前的行为逐字段一致 —— 按模型名解析仍可用，只是不知道多配置表里
     * 哪一套生效。生产环境永远走上面那个带 {@code ObjectProvider} 的构造器。
     */
    public ModelConfigService(AiModelConfigRepository repository,
                              OpenAiChatModelFactory modelFactory,
                              AiChatClientProvider provider,
                              Clock clock,
                              String envBaseUrl,
                              String envApiKey,
                              String envChatModel,
                              String envEmbeddingModel,
                              String envTemperature) {
        this(repository, modelFactory, provider, clock, null, null,
                envBaseUrl, envApiKey, envChatModel, envEmbeddingModel, envTemperature);
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

        // 语音模型**没有内置默认**：内置一个语音模型名等于替用户决定「用谁的额度
        // 上传你的录音」。没有配置就是 null，调用方据此回落到浏览器自带识别。
        String audioModel = blankToNull(values.audioModel());

        // 每一项都记下「来源」，前端据此显示「来自环境变量」还是「设置页覆盖」。
        // 没有这个信息，用户改了 .env 发现不生效时无从判断是被覆盖了。
        Map<String, ConfigSource> sources = new LinkedHashMap<>();
        sources.put("baseUrl", sourceOf(values.baseUrl(), envBaseUrl, KEY_BASE_URL));
        sources.put("apiKey", sourceOf(values.apiKey(), envApiKey, KEY_API_KEY));
        sources.put("chatModel", sourceOf(values.chatModel(), envChatModel, KEY_CHAT_MODEL));
        sources.put("embeddingModel",
                sourceOf(values.embeddingModel(), envEmbeddingModel, KEY_EMBEDDING_MODEL));
        sources.put("audioModel", audioModel == null ? ConfigSource.DEFAULT : ConfigSource.DATABASE);
        sources.put("temperature", values.temperature() != null
                ? ConfigSource.DATABASE
                : (envTemperature != null ? ConfigSource.ENVIRONMENT : ConfigSource.DEFAULT));

        return new ResolvedConfig(baseUrl, apiKey, chatModel, temperature, embeddingModel,
                audioModel, values.revision(), values.updatedAt(), sources);
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

    // ------------------------------------------------------------------ 按模型名构造（V23）

    /**
     * 取当前生效配置。**给非聊天链路的调用方用**（如语音转写）。
     *
     * <p>与 {@link #resolve()} 的区别：那个只看 V20 单行表，本方法优先读
     * V21 多配置表里生效的那行（没有才回落）。设置页保存的是多配置表，
     * 所以凡是要「用户当前真正在用的那套」，必须走这里。
     *
     * <p>{@link #resolve(String)} / {@link #defaultChatModel()} 内部用的也是
     * 同一套逻辑，只是那两处是 {@link com.personalos.common.ai.RuntimeChatModelResolver}
     * 的契约，签名不能改。
     */
    public ResolvedConfig current() {
        return currentResolved();
    }

    /**
     * 按模型名造一个模型实例，复用**当前生效配置**的端点、密钥与温度。
     *
     * <h2>为什么只换模型名，其余沿用</h2>
     * 会话级选模型的语义是「这套 API 配置下的另一个模型」—— 端点与密钥是配置级
     * （同一把 key 能用这个端点的所有模型），温度是用户对这一套配置的偏好。
     * 只有模型名是会话级的选择。
     *
     * <h2>为什么每次都重新解析配置</h2>
     * 用户可能在会话打开的这段时间里切换了生效配置。缓存一份「造模型用的凭据」
     * 会让新会话继续用旧端点 —— 而环境变量与数据库都可能变，重新解析是唯一
     * 能保证一致的做法。它的成本是一次数据库读，相对一次模型调用可忽略。
     *
     * @param modelName 模型名
     * @return 新建的模型实例
     * @throws BusinessException 模型名为空、或当前配置没有密钥
     */
    @Override
    public org.springframework.ai.chat.model.ChatModel resolve(String modelName) {
        String trimmed = modelName == null ? "" : modelName.strip();
        if (trimmed.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "模型名不能为空");
        }
        ResolvedConfig resolved = currentResolved();
        if (!resolved.hasApiKey()) {
            // 没有密钥时构造出来的客户端任何请求都会 401，不如在这里给出可读的原因
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED,
                    "当前配置没有可用的 API Key，无法按模型名切换");
        }
        return modelFactory.create(resolved.baseUrl(), resolved.apiKey(), trimmed,
                resolved.temperature(), Map.of());
    }

    /** 当前生效配置的默认对话模型名（会话没选模型时用它） */
    @Override
    public String defaultChatModel() {
        return currentResolved().chatModel();
    }

    /**
     * 取当前生效配置。
     *
     * <p>与 {@link ModelProfileService#resolveCurrent} 走的是同一套语义（优先
     * 多配置表里生效的那行，没有则回落 V20 老表），但本类拿不到多配置表 ——
     * 那是上层的职责。为了不在两个类之间制造循环依赖，这里用
     * {@code ObjectProvider} 懒取那个服务，取不到时退回只有环境变量的解析结果。
     *
     * <p>懒取而不是构造注入：{@code ModelProfileService} 依赖本类
     * （要 {@code resolveFrom} / {@code applyResolved}），构造期互注会形成循环。
     */
    private ResolvedConfig currentResolved() {
        if (profileServiceProvider != null) {
            ModelProfileService profiles = profileServiceProvider.getIfAvailable();
            if (profiles != null) {
                return profiles.resolveCurrent();
            }
        }
        return resolve();
    }

    /**
     * 启动完成后把本类注册为 provider 的「按名造模型」工厂（V23）。
     *
     * <p>放在 {@code ApplicationReadyEvent} 而不是构造器或 {@code @PostConstruct}：
     * 那时配置服务与被它依赖的 provider 都已经装配完成，不存在半初始化状态。
     * 用事件也顺带避免了在构造期就去碰 {@code ObjectProvider} 里的懒加载代理。
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void registerResolver() {
        provider.setModelResolver(this);
        log.debug("已注册按模型名构造聊天模型的工厂");
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

    /**
     * 带「真实环境变量名」的版本：能区分「yml 占位符的默认值」与「用户真的设了变量」。
     *
     * <p>见 {@link #environment} 字段的说明。判断顺序：
     * <ol>
     *   <li>库里有值 → {@code DATABASE}（设置页覆盖，优先级最高）；</li>
     *   <li>变量名在属性源里真实存在 → {@code ENVIRONMENT}；</li>
     *   <li>否则 {@code DEFAULT} —— 此时那个值只可能是 yml 里的兜底默认。</li>
     * </ol>
     *
     * @param dbValue 库里存的值
     * @param resolvedValue Spring 展开占位符后的生效值
     * @param envKey   与 yml 占位符同名的原始变量名
     */
    private ConfigSource sourceOf(String dbValue, String resolvedValue, String envKey) {
        if (dbValue != null && !dbValue.isBlank()) {
            return ConfigSource.DATABASE;
        }
        if (environment == null) {
            // 测试用构造器：没有 Environment 可查，退回旧语义（值非空即环境变量）
            return resolvedValue != null ? ConfigSource.ENVIRONMENT : ConfigSource.DEFAULT;
        }
        return isEnvDefined(envKey) ? ConfigSource.ENVIRONMENT : ConfigSource.DEFAULT;
    }

    /**
     * 该变量名是否真的被定义过（系统环境变量 / .env / 命令行 -D 都算）。
     *
     * <p><b>不看值是否为空</b>：用户写了 {@code OPENAI_API_KEY=} 是一个明确的
     * 「我就是要它为空」的表达，来源仍是环境变量，不该标成内置默认。
     */
    private boolean isEnvDefined(String key) {
        if (environment == null) {
            return false;
        }
        String raw = environment.getProperty(key);
        return raw != null && !raw.isBlank();
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
                  String audioModel,
                  Integer revision,
                  Instant updatedAt) {

        /** 「没有任何配置」—— 全部回落到环境变量与内置默认 */
        static final Values EMPTY = new Values(null, null, null, null, null, null, null, null);

        /**
         * 从 V20 单行表实体取值。
         *
         * <p>两个实体各一个**具名**工厂，而不是同名 {@code of} 重载：
         * 调用点的 `entity == null ? EMPTY : of(entity)` 里 {@code null} 在
         * 两个重载间是二义的，编译直接失败。具名方法没有这个问题，
         * 而且读起来就写明「这份值来自哪张表」。
         */
        static Values fromLegacy(AiModelConfigEntity entity) {
            // V20 单行表没有 audio_model 列（语音模型是 V24 才有的），
            // 传 null 表示「这张表不提供该项」—— 与「用户清空了该项」在
            // merge 里都落到同一个结果，所以无需区分。
            return new Values(entity.getBaseUrl(), entity.getApiKey(), entity.getChatModel(),
                    entity.getTemperature(), entity.getEmbeddingModel(), null,
                    entity.getRevision(), entity.getUpdatedAt());
        }

        /** 从 V21 多配置实体取值 */
        static Values fromProfile(AiModelProfileEntity entity) {
            return new Values(entity.getBaseUrl(), entity.getApiKey(), entity.getChatModel(),
                    entity.getTemperature(), entity.getEmbeddingModel(), entity.getAudioModel(),
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
     * @param audioModel     语音模型名（V24）；null 表示没配，转写回落到浏览器自带识别
     * @param revision       数据库配置版本；null 表示数据库里没有配置行
     * @param updatedAt      最后一次保存时间
     * @param sources        各字段的来源
     */
    public record ResolvedConfig(String baseUrl,
                                 String apiKey,
                                 String chatModel,
                                 Double temperature,
                                 String embeddingModel,
                                 String audioModel,
                                 Integer revision,
                                 Instant updatedAt,
                                 Map<String, ConfigSource> sources) {

        /** 当前是否配了密钥（不暴露密钥本身） */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 是否配了语音模型（决定转写走后端还是回落浏览器识别） */
        public boolean hasAudioModel() {
            return audioModel != null && !audioModel.isBlank();
        }

        /** 兼容构造器：不带语音模型（V23 及以前的调用方与测试） */
        public ResolvedConfig(String baseUrl, String apiKey, String chatModel, Double temperature,
                              String embeddingModel, Integer revision, Instant updatedAt,
                              Map<String, ConfigSource> sources) {
            this(baseUrl, apiKey, chatModel, temperature, embeddingModel, null,
                    revision, updatedAt, sources);
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
