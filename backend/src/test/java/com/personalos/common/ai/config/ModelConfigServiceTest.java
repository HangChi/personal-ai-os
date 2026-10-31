package com.personalos.common.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.ai.AiProperties;
import com.personalos.common.ai.ToolContributor;
import com.personalos.common.ai.config.ModelConfigService.ConfigSource;
import com.personalos.common.ai.config.ModelConfigService.ResolvedConfig;
import com.personalos.common.ai.config.ModelConfigService.SaveCommand;
import com.personalos.support.StaticObjectProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 模型配置服务的纯单元测试（无 Spring 容器、无数据库、无真实 HTTP）。
 *
 * <h2>为什么这些用例必须存在</h2>
 * 三层取值回落与密钥三态是本轮最容易被改坏的两处，而它们坏掉时**不会报错**：
 * <ul>
 *   <li>逐字段回落写成「整份覆盖」→「只改了模型名，端点却被打回默认」；</li>
 *   <li>密钥三态写错 →「每保存一次别的字段就把密钥清掉」。</li>
 * </ul>
 * 两者在界面上都只表现为「值不对」，没有任何异常可抓，只能靠单测钉住。
 */
class ModelConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private AiModelConfigRepository repository;
    private AiChatClientProvider provider;
    private OpenAiChatModelFactory factory;

    /** 当前内存里的那一行（模拟单行表） */
    private AiModelConfigEntity row;

    @BeforeEach
    void setUp() {
        row = null;
        repository = mock(AiModelConfigRepository.class);
        // 必须给 findSingleton 设桩，而不是 findById：
        // findSingleton 是接口上的 **default 方法**，Mockito 不会调用它（默认回答对 Optional
        // 直接返回 Optional.empty()），因此给 findById 设的桩永远不会被走到，
        // 症状是「保存后再读却读不到」——很容易被误判成服务层的 bug。
        when(repository.findSingleton()).thenAnswer(invocation -> Optional.ofNullable(row));
        when(repository.save(any(AiModelConfigEntity.class))).thenAnswer(invocation -> {
            row = invocation.getArgument(0);
            return row;
        });

        factory = mock(OpenAiChatModelFactory.class);
        // 默认让工厂返回一个假模型，避免任何用例意外走到「抛异常」分支。
        // 返回类型是 OpenAiChatModel（工厂的真实返回类型），不是 ChatModel —— 用后者编译不过。
        when(factory.create(any(), any(), any(), any(), any()))
                .thenReturn(mock(org.springframework.ai.openai.OpenAiChatModel.class));

        provider = new AiChatClientProvider(
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                mock(ChatMemory.class),
                // AiProperties 的 compact constructor 会把 null 补成默认值
                new AiProperties(null, null),
                new com.personalos.common.ai.ContextBudgetService(new AiProperties(null, null)));
    }

    // ------------------------------------------------------------------ 三层回落

    /** 环境变量全空：所有字段都该回落到内置默认 */
    @Test
    @DisplayName("无数据库行且无环境变量时，全部字段回落到内置默认")
    void fallsBackToBuiltInDefaults() {
        ResolvedConfig resolved = service("", "", "", "", "").resolve();

        assertThat(resolved.baseUrl()).isEqualTo(ModelConfigService.DEFAULT_BASE_URL);
        assertThat(resolved.chatModel()).isEqualTo(ModelConfigService.DEFAULT_CHAT_MODEL);
        assertThat(resolved.embeddingModel()).isEqualTo(ModelConfigService.DEFAULT_EMBEDDING_MODEL);
        assertThat(resolved.apiKey()).isEmpty();
        assertThat(resolved.temperature()).isNull();
        assertThat(resolved.revision()).isNull();
        assertThat(resolved.sources().get("baseUrl")).isEqualTo(ConfigSource.DEFAULT);
        assertThat(resolved.sources().get("apiKey")).isEqualTo(ConfigSource.DEFAULT);
        assertThat(resolved.hasApiKey()).isFalse();
    }

    @Test
    @DisplayName("环境变量优先于内置默认，来源标 ENVIRONMENT")
    void usesEnvironmentWhenNoDatabaseRow() {
        ResolvedConfig resolved = service(
                "https://env.example.com", "sk-env", "env-model", "env-embed", "0.3").resolve();

        assertThat(resolved.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(resolved.apiKey()).isEqualTo("sk-env");
        assertThat(resolved.chatModel()).isEqualTo("env-model");
        assertThat(resolved.embeddingModel()).isEqualTo("env-embed");
        assertThat(resolved.temperature()).isEqualTo(0.3);
        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.ENVIRONMENT)
                .containsEntry("apiKey", ConfigSource.ENVIRONMENT)
                .containsEntry("chatModel", ConfigSource.ENVIRONMENT)
                .containsEntry("embeddingModel", ConfigSource.ENVIRONMENT)
                .containsEntry("temperature", ConfigSource.ENVIRONMENT);
    }

    /**
     * 核心语义：**逐字段**回落，不是「有数据库行就整份用数据库的」。
     *
     * <p>数据库只覆盖了模型名，端点与密钥仍应取环境变量。若把 merge 改成整份覆盖，
     * 这条会立刻失败 —— 那正是本次最容易犯的错。
     */
    @Test
    @DisplayName("数据库只覆盖部分字段时，其余字段仍取环境变量")
    void mergesPerField() {
        ModelConfigService service = service(
                "https://env.example.com", "sk-env", "env-model", "env-embed", "0.3");
        AiModelConfigEntity entity = new AiModelConfigEntity(NOW);
        entity.overwrite(null, null, "db-model", null, null, NOW);
        row = entity;

        ResolvedConfig resolved = service.resolve();

        assertThat(resolved.chatModel()).isEqualTo("db-model");
        assertThat(resolved.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(resolved.apiKey()).isEqualTo("sk-env");
        assertThat(resolved.embeddingModel()).isEqualTo("env-embed");
        assertThat(resolved.temperature()).isEqualTo(0.3);
        assertThat(resolved.sources())
                .containsEntry("chatModel", ConfigSource.DATABASE)
                .containsEntry("baseUrl", ConfigSource.ENVIRONMENT);
    }

    @Test
    @DisplayName("数据库覆盖全部字段时，来源全部标 DATABASE（语音模型除外，见下）")
    void databaseOverridesEverything() {
        ModelConfigService service = service(
                "https://env.example.com", "sk-env", "env-model", "env-embed", "0.3");

        service.save(new SaveCommand("https://db.example.com", "sk-db", "db-model", 1.2, "db-embed"));
        ResolvedConfig resolved = service.resolve();

        assertThat(resolved.baseUrl()).isEqualTo("https://db.example.com");
        assertThat(resolved.apiKey()).isEqualTo("sk-db");
        assertThat(resolved.chatModel()).isEqualTo("db-model");
        assertThat(resolved.temperature()).isEqualTo(1.2);
        assertThat(resolved.revision()).isEqualTo(1);
        // 语音模型（V24）不在 V20 单行表里，也没有环境变量兜底，
        // 所以它的来源必然是 DEFAULT。用 containsOnly 会把这条差异误判成失败，
        // 这里逐项断言「其余全部 DATABASE」，并单独钉住语音模型那一项。
        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.DATABASE)
                .containsEntry("apiKey", ConfigSource.DATABASE)
                .containsEntry("chatModel", ConfigSource.DATABASE)
                .containsEntry("embeddingModel", ConfigSource.DATABASE)
                .containsEntry("temperature", ConfigSource.DATABASE)
                .containsEntry("audioModel", ConfigSource.DEFAULT);
    }

    @Test
    @DisplayName("语音模型没有内置默认：未配置时为 null 且来源为 DEFAULT")
    void audioModelHasNoBuiltInDefault() {
        ModelConfigService service = service("https://env.example.com", "sk-env", "m", "e", "");

        ResolvedConfig resolved = service.resolve();

        // 内置一个语音模型名等于替用户决定「用谁的额度上传录音」，这是不可接受的
        assertThat(resolved.audioModel()).isNull();
        assertThat(resolved.hasAudioModel()).isFalse();
        assertThat(resolved.sources()).containsEntry("audioModel", ConfigSource.DEFAULT);
    }

    @Test
    @DisplayName("多配置表里配了语音模型时，它出现在生效配置里")
    void audioModelFromProfile() {
        ModelConfigService service = service("https://env.example.com", "sk-env", "m", "e", "");
        AiModelProfileEntity profile = new AiModelProfileEntity(
                "主力", null, 0, java.time.Instant.now());
        profile.overwrite("主力", null, "https://db.example.com", "sk-db", "db-model",
                null, "db-embed", "paraformer-v2", "OPENAI", java.time.Instant.now());

        ResolvedConfig resolved = service.resolveFrom(profile);

        assertThat(resolved.audioModel()).isEqualTo("paraformer-v2");
        assertThat(resolved.hasAudioModel()).isTrue();
        assertThat(resolved.sources()).containsEntry("audioModel", ConfigSource.DATABASE);
    }

    // ------------------------------------------------------------------ revision

    @Test
    @DisplayName("首次保存 revision 为 1，之后逐次递增")
    void revisionStartsAtOne() {
        ModelConfigService service = service("", "", "", "", "");

        ResolvedConfig first = service.save(new SaveCommand("https://a.example.com", "sk-1", "m", null, null));
        assertThat(first.revision()).isEqualTo(1);

        ResolvedConfig second = service.save(new SaveCommand("https://a.example.com", "sk-1", "m", null, null));
        assertThat(second.revision()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 密钥三态

    /**
     * 密钥传 {@code null} 表示「不改动」。
     *
     * <p>前端不回填明文，所以「用户没碰密钥框」必然以 null 提交。
     * 若把 null 当成「清空」，用户每改一次模型名就丢一次密钥。
     */
    @Test
    @DisplayName("apiKey 传 null 时保留原密钥")
    void nullApiKeyKeepsExisting() {
        ModelConfigService service = service("", "", "", "", "");
        service.save(new SaveCommand("https://a.example.com", "sk-original", "m", null, null));

        service.save(new SaveCommand("https://a.example.com", null, "m2", null, null));

        ResolvedConfig resolved = service.resolve();
        assertThat(resolved.apiKey()).isEqualTo("sk-original");
        assertThat(resolved.chatModel()).isEqualTo("m2");
    }

    @Test
    @DisplayName("apiKey 传空串时清空，回落到环境变量")
    void blankApiKeyClearsAndFallsBack() {
        ModelConfigService service = service("", "sk-env", "", "", "");
        service.save(new SaveCommand("https://a.example.com", "sk-db", "m", null, null));
        assertThat(service.resolve().apiKey()).isEqualTo("sk-db");

        service.save(new SaveCommand("https://a.example.com", "   ", "m", null, null));

        ResolvedConfig resolved = service.resolve();
        assertThat(resolved.apiKey()).isEqualTo("sk-env");
        assertThat(resolved.sources().get("apiKey")).isEqualTo(ConfigSource.ENVIRONMENT);
    }

    @Test
    @DisplayName("apiKey 传非空时覆盖")
    void nonBlankApiKeyOverwrites() {
        ModelConfigService service = service("", "", "", "", "");
        service.save(new SaveCommand(null, "sk-one", null, null, null));
        service.save(new SaveCommand(null, "sk-two", null, null, null));

        assertThat(service.resolve().apiKey()).isEqualTo("sk-two");
    }

    // ------------------------------------------------------------------ 多配置表解析

    /**
     * V21 多配置表与 V20 单行表走**同一个** merge。
     *
     * <p>这条断言的价值在于防「为两种实体各写一份回落逻辑」：两份实现迟早会
     * 走偏，而且症状是在某条路径上静默用了错的默认值 —— 没有任何异常可抓。
     */
    @Test
    @DisplayName("resolveFrom(profile) 与旧表走同一套三层回落")
    void resolveFromProfileUsesSameFallback() {
        ModelConfigService service = service(
                "https://env.example.com", "sk-env", "env-model", "env-embed", "0.3");
        AiModelProfileEntity profile = new AiModelProfileEntity(
                "测试配置", "dashscope", 0, NOW);
        profile.overwrite("测试配置", "dashscope", "https://db.example.com", null,
                "db-model", null, null, NOW);

        ResolvedConfig resolved = service.resolveFrom(profile);

        assertThat(resolved.baseUrl()).isEqualTo("https://db.example.com");
        assertThat(resolved.apiKey()).as("profile 没填密钥，应回落环境变量").isEqualTo("sk-env");
        assertThat(resolved.chatModel()).isEqualTo("db-model");
        assertThat(resolved.embeddingModel()).isEqualTo("env-embed");
        assertThat(resolved.temperature()).isEqualTo(0.3);
        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.DATABASE)
                .containsEntry("apiKey", ConfigSource.ENVIRONMENT)
                .containsEntry("chatModel", ConfigSource.DATABASE);
    }

    @Test
    @DisplayName("resolveFrom(null) 等价于「没有任何配置」，全部回落到环境变量")
    void resolveFromNullProfileFallsBackToEnvironment() {
        ModelConfigService service = service(
                "https://env.example.com", "sk-env", "env-model", "", "");

        ResolvedConfig resolved = service.resolveFrom(null);

        assertThat(resolved.baseUrl()).isEqualTo("https://env.example.com");
        assertThat(resolved.apiKey()).isEqualTo("sk-env");
        assertThat(resolved.chatModel()).isEqualTo("env-model");
        assertThat(resolved.revision()).isNull();
    }

    @Test
    @DisplayName("resolveFromLegacy(null) 等价于没有旧配置行")
    void resolveFromNullLegacyFallsBackToDefaults() {
        ModelConfigService service = service("", "", "", "", "");

        ResolvedConfig resolved = service.resolveFromLegacy(null);

        assertThat(resolved.baseUrl()).isEqualTo(ModelConfigService.DEFAULT_BASE_URL);
        assertThat(resolved.chatModel()).isEqualTo(ModelConfigService.DEFAULT_CHAT_MODEL);
    }

    @Test
    @DisplayName("applyResolved 与 applyToRuntime 同义")
    void applyResolvedIsAliasOfApplyToRuntime() {
        ModelConfigService service = service("https://env.example.com", "sk-env", "env-model", "", "");

        service.applyResolved(service.resolve());

        verify(factory).create("https://env.example.com", "sk-env", "env-model", null, java.util.Map.of());
    }

    // ------------------------------------------------------------------ 掩码

    @Test
    @DisplayName("掩码保留末 4 位")
    void masksKeepingTail() {
        assertThat(ModelConfigService.mask("sk-ws-abcdefghijKLmg")).isEqualTo("****KLmg");
    }

    @Test
    @DisplayName("空密钥掩码为空串")
    void maskBlankReturnsEmpty() {
        assertThat(ModelConfigService.mask(null)).isEmpty();
        assertThat(ModelConfigService.mask("")).isEmpty();
        assertThat(ModelConfigService.mask("   ")).isEmpty();
    }

    @Test
    @DisplayName("极短密钥整体打码，不暴露任何字符")
    void maskShortKeyHidesAll() {
        assertThat(ModelConfigService.mask("abcd")).isEqualTo("****");
        assertThat(ModelConfigService.mask("ab")).isEqualTo("****");
    }

    // ------------------------------------------------------------------ 运行期应用

    @Test
    @DisplayName("没有密钥时不重建模型")
    void skipsRuntimeApplyWithoutKey() {
        ModelConfigService service = service("", "", "some-model", "", "");

        service.applyToRuntime(service.resolve());

        verify(factory, never()).create(any(), any(), any(), any(), any());
        assertThat(provider.currentModel()).isNull();
    }

    @Test
    @DisplayName("有密钥时重建模型并替换到 provider")
    void appliesToRuntimeWithKey() {
        ModelConfigService service = service("https://env.example.com", "sk-env", "env-model", "", "");

        service.applyToRuntime(service.resolve());

        verify(factory).create("https://env.example.com", "sk-env", "env-model", null, java.util.Map.of());
        assertThat(provider.currentModel()).isNotNull();
    }

    @Test
    @DisplayName("工厂抛异常时转成业务异常，且提示语点明「配置已保存」")
    void wrapsFactoryFailure() {
        when(factory.create(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        ModelConfigService service = service("https://env.example.com", "sk-env", "env-model", "", "");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.applyToRuntime(service.resolve())))
                .isInstanceOf(com.personalos.common.exception.BusinessException.class)
                .hasMessageContaining("配置已保存")
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("启动期无数据库行时不动模型")
    void startupWithoutDatabaseRowKeepsModel() {
        ModelConfigService service = service("https://env.example.com", "sk-env", "env-model", "", "");

        service.applyOnStartup();

        verify(factory, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("启动期工厂失败只记录不抛出，不阻断启动")
    void startupFailureDoesNotThrow() {
        when(factory.create(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        ModelConfigService service = service("https://env.example.com", "sk-env", "env-model", "", "");
        service.save(new SaveCommand("https://env.example.com", "sk-env", "env-model", null, null));

        // 启动失败不该升级成「整个应用不可用」
        org.assertj.core.api.Assertions.assertThatCode(service::applyOnStartup).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("环境变量温度写错时不阻断，回落未设置")
    void invalidTemperatureFallsBackToNull() {
        ResolvedConfig resolved = service("", "", "", "", "not-a-number").resolve();

        assertThat(resolved.temperature()).isNull();
        assertThat(resolved.sources().get("temperature")).isEqualTo(ConfigSource.DEFAULT);
    }

    // ------------------------------------------------------------------ 替身

    private ModelConfigService service(String baseUrl, String apiKey, String chatModel,
                                       String embeddingModel, String temperature) {
        return new ModelConfigService(repository, factory, provider, CLOCK,
                baseUrl, apiKey, chatModel, embeddingModel, temperature);
    }

    // -------------------------------------------- 来源判定：默认值 vs 真环境变量

    /**
     * 带 {@link org.springframework.core.env.Environment} 的构造：
     * 「值非空」不再等于「来自环境变量」。
     *
     * <p><b>不能用 {@code StandardEnvironment.getSystemProperties().put(...)}</b>：
     * 那个 map 就是真实的 {@code System.getProperties()}，写入会跨测试类泄漏 ——
     * 表现为「单独跑绿、全量跑红」的幽灵失败。这里改用只读的
     * {@link org.springframework.mock.env.MockEnvironment}，互不干扰。
     *
     * @param envVars 真实定义过的变量名集合
     */
    private ModelConfigService serviceWithEnv(java.util.Set<String> envVars,
                                              String baseUrl, String apiKey,
                                              String chatModel, String embeddingModel,
                                              String temperature) {
        org.springframework.mock.env.MockEnvironment environment =
                new org.springframework.mock.env.MockEnvironment();
        envVars.forEach((key) -> environment.withProperty(key, "defined"));
        return new ModelConfigService(repository, factory, provider, CLOCK, null,
                environment, baseUrl, apiKey, chatModel, embeddingModel, temperature);
    }

    /**
     * 这是本轮修的那个 bug 的回归测试。
     *
     * <p>{@code application.yml} 写的是 {@code base-url: ${OPENAI_BASE_URL:https://api.openai.com}}，
     * 所以即便用户完全没设 {@code OPENAI_BASE_URL}，注入进来的值也非空。
     * 修之前这里会被标成 {@code ENVIRONMENT}，去设置页看到「端点来自环境变量」，
     * 而去改 {@code .env} 又没反应 —— 徽章在反向误导。
     */
    @Test
    @DisplayName("值非空但变量未真实定义时，来源标 DEFAULT 而不是 ENVIRONMENT")
    void nonBlankValueWithoutDefinedVariableIsDefault() {
        // 模拟「yml 兜底值被展开」：传进来的值非空，但环境里没有这个变量名
        ResolvedConfig resolved = serviceWithEnv(java.util.Set.of(),
                "https://api.openai.com", "", "gpt-4o-mini", "text-embedding-3-small", "")
                .resolve();

        assertThat(resolved.baseUrl()).isEqualTo("https://api.openai.com");
        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.DEFAULT)
                .containsEntry("chatModel", ConfigSource.DEFAULT)
                .containsEntry("embeddingModel", ConfigSource.DEFAULT)
                .containsEntry("apiKey", ConfigSource.DEFAULT);
    }

    @Test
    @DisplayName("变量真实定义时来源标 ENVIRONMENT")
    void definedVariableIsEnvironment() {
        ResolvedConfig resolved = serviceWithEnv(
                java.util.Set.of("OPENAI_BASE_URL", "OPENAI_API_KEY", "OPENAI_CHAT_MODEL"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1", "sk-real",
                "qwen3.8-flash", "text-embedding-3-small", "")
                .resolve();

        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.ENVIRONMENT)
                .containsEntry("apiKey", ConfigSource.ENVIRONMENT)
                .containsEntry("chatModel", ConfigSource.ENVIRONMENT)
                // 这一项没定义，仍应是 DEFAULT —— 逐字段判定，不搞「有一个就全标」
                .containsEntry("embeddingModel", ConfigSource.DEFAULT);
    }

    @Test
    @DisplayName("库里有值时永远标 DATABASE，与环境变量是否存在无关")
    void databaseWinsOverEnvironmentForSource() {
        AiModelConfigEntity entity = new AiModelConfigEntity(CLOCK.instant());
        entity.overwrite("https://db.example.com", "sk-db", "db-model", 0.5,
                "db-embed", CLOCK.instant());
        org.mockito.Mockito.when(repository.findSingleton())
                .thenReturn(java.util.Optional.of(entity));

        ModelConfigService withEnv = serviceWithEnv(
                java.util.Set.of("OPENAI_BASE_URL", "OPENAI_API_KEY"),
                "https://env.example.com", "sk-env", "env-model", "env-embed", "0.3");
        ResolvedConfig resolved = withEnv.resolve();

        assertThat(resolved.sources())
                .containsEntry("baseUrl", ConfigSource.DATABASE)
                .containsEntry("apiKey", ConfigSource.DATABASE);
    }

    /** 永远是「没有这个 Bean」的 ObjectProvider，等价于 AI_CHAT_PROVIDER=none */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return (ObjectProvider<T>) new StaticObjectProvider<>(List.of());
    }
}
