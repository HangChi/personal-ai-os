package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.support.StaticObjectProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 会话级选模型（V23）的单元测试。
 *
 * <h2>为什么这些用例必须存在</h2>
 * 按模型名取客户端这条路径有三处「写错了不报错」的失效：
 * <ul>
 *   <li><b>按名造出来的实例没被复用</b>：每次请求新建一份模型与连接池，
 *       聊二十个会话就是二十个永不关闭的池 —— 只在内存曲线和连接数上体现；</li>
 *   <li><b>换了配置但缓存没失效</b>：用户改了端点，会话仍在用旧端点发请求，
 *       报错是上游给的（401 / 404），指向完全无关的方向；</li>
 *   <li><b>思考参数取错了模型的选项</b>：请求里带着<b>默认模型</b>的名字，
 *       界面上显示 A、账单与回答都来自 B。这是本类最需要钉住的一条，
 *       因为它需要抓上游请求体才能发现。</li>
 * </ul>
 */
class AiChatClientProviderModelSelectionTest {

    private AiChatClientProvider provider;
    private RecordingResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RecordingResolver();
        provider = new AiChatClientProvider(
                providerOf(new FakeModel("default-model", "sk-test")),
                emptyProvider(), emptyProvider(), emptyProvider(),
                mock(ChatMemory.class), new AiProperties(null, null),
                new com.personalos.common.ai.ContextBudgetService(new AiProperties(null, null)));
        provider.setModelResolver(resolver);
    }

    // ------------------------------------------------------------------ 基本路径

    @Test
    @DisplayName("模型名为空时用当前默认模型，不触发按名解析")
    void blankNameUsesDefault() {
        AiChatClientProvider.ChatTarget nullTarget = provider.targetFor(null);
        AiChatClientProvider.ChatTarget blankTarget = provider.targetFor("   ");

        assertThat(modelNameOf(nullTarget.model())).isEqualTo("default-model");
        assertThat(modelNameOf(blankTarget.model())).isEqualTo("default-model");
        assertThat(resolver.calls.get())
                .as("空名代表「沿用默认」，不该白跑一次解析")
                .isZero();
    }

    @Test
    @DisplayName("模型名与默认模型相同时复用现有实例，不重新构造")
    void sameAsDefaultReusesBundle() {
        AiChatClientProvider.ChatTarget target = provider.targetFor("default-model");

        assertThat(modelNameOf(target.model())).isEqualTo("default-model");
        assertThat(resolver.calls.get()).isZero();
    }

    @Test
    @DisplayName("指定别的模型时按名构造，并让模型名真的换过去")
    void differentNameResolvesNewModel() {
        AiChatClientProvider.ChatTarget target = provider.targetFor("qwen-max");

        assertThat(modelNameOf(target.model())).isEqualTo("qwen-max");
        assertThat(resolver.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("同一个模型名第二次请求复用缓存，不重复构造连接池")
    void secondCallHitsCache() {
        ChatModel first = provider.targetFor("qwen-max").model();
        ChatModel second = provider.targetFor("qwen-max").model();

        assertThat(second).isSameAs(first);
        assertThat(resolver.calls.get())
                .as("同一模型名必须复用，否则每个会话各自开一份连接池")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("不同模型名各自一份实例")
    void differentNamesGetDifferentInstances() {
        ChatModel max = provider.targetFor("qwen-max").model();
        ChatModel plus = provider.targetFor("qwen-plus").model();

        assertThat(max).isNotSameAs(plus);
        assertThat(modelNameOf(max)).isEqualTo("qwen-max");
        assertThat(modelNameOf(plus)).isEqualTo("qwen-plus");
        assertThat(resolver.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("模型名两端空白被裁掉，前后空格不会造出第二份缓存")
    void nameIsTrimmed() {
        ChatModel first = provider.targetFor("qwen-max").model();
        ChatModel second = provider.targetFor("  qwen-max  ").model();

        assertThat(second).isSameAs(first);
        assertThat(resolver.calls.get()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 缓存失效

    @Test
    @DisplayName("切换模型（配置变更）后按名缓存全部作废")
    void replaceModelInvalidatesNamedCache() {
        ChatModel before = provider.targetFor("qwen-max").model();

        provider.replaceModel(new FakeModel("another-default", "sk-other"));
        ChatModel after = provider.targetFor("qwen-max").model();

        assertThat(after)
                .as("换了配置还用旧的按名实例，等于把请求发到旧端点")
                .isNotSameAs(before);
        assertThat(resolver.calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("invalidateNamedBundles 可单独调用，效果同样是一次全新构造")
    void explicitInvalidationForcesRebuild() {
        provider.targetFor("qwen-max");
        provider.invalidateNamedBundles();
        provider.targetFor("qwen-max");

        assertThat(resolver.calls.get()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 降级路径

    @Test
    @DisplayName("解析失败时退回默认模型，不让整个会话打不开")
    void resolverFailureFallsBackToDefault() {
        resolver.failure = new IllegalStateException("模型名不存在");

        AiChatClientProvider.ChatTarget target = provider.targetFor("nonexistent");

        assertThat(modelNameOf(target.model()))
                .as("一次失败的选模型不该让会话直接报错，用默认模型回答更好")
                .isEqualTo("default-model");
    }

    @Test
    @DisplayName("没有解析器时退回默认模型")
    void missingResolverFallsBackToDefault() {
        AiChatClientProvider bare = new AiChatClientProvider(
                providerOf(new FakeModel("default-model", "sk-test")),
                emptyProvider(), emptyProvider(), emptyProvider(),
                mock(ChatMemory.class), new AiProperties(null, null),
                new com.personalos.common.ai.ContextBudgetService(new AiProperties(null, null)));

        assertThat(modelNameOf(bare.targetFor("qwen-max").model())).isEqualTo("default-model");
    }

    @Test
    @DisplayName("完全没有可用模型时抛 AI_NOT_CONFIGURED")
    void noModelConfiguredThrows() {
        AiChatClientProvider bare = new AiChatClientProvider(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                mock(ChatMemory.class), new AiProperties(null, null),
                new com.personalos.common.ai.ContextBudgetService(new AiProperties(null, null)));

        assertThatThrownBy(() -> bare.targetFor("qwen-max"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AI_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("replaceModel 拒绝 null，避免把聊天能力静默打空")
    void replaceModelRejectsNull() {
        assertThatThrownBy(() -> provider.replaceModel(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("没有模型时 isConfigured 为 false")
    void isConfiguredReflectsState() {
        assertThat(provider.isConfigured()).isTrue();

        AiChatClientProvider bare = new AiChatClientProvider(
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                mock(ChatMemory.class), new AiProperties(null, null),
                new com.personalos.common.ai.ContextBudgetService(new AiProperties(null, null)));

        assertThat(bare.isConfigured()).isFalse();
    }

    // ------------------------------------------------------------------ 思考参数

    @Test
    @DisplayName("思考参数按传入模型的选项合并，不会写回默认模型的名字")
    void thinkingOptionsUseGivenModelNotDefault() {
        AiChatClientProvider.ChatTarget target = provider.targetFor("qwen-max");
        ChatClient.ChatClientRequestSpec request = capturingRequest();

        provider.applyThinkingOptions(request, 512, target.model());

        OpenAiChatOptions applied = lastAppliedOptions(request);
        assertThat(applied.getModel())
                .as("这条断言是本类最重要的一个："
                        + "若取的是当前默认模型的选项，请求里会带着 default-model"
                        + "——界面上显示 qwen-max，账单却来自默认模型，且不报错")
                .isEqualTo("qwen-max");
        assertThat(applied.getExtraBody())
                .as("思考预算必须进请求，否则「深度思考」档形同虚设")
                .containsEntry("thinking_budget", 512);
    }

    @Test
    @DisplayName("思考预算为 0 时仍打开流式用量上报")
    void zeroBudgetStillEnablesStreamUsage() {
        AiChatClientProvider.ChatTarget target = provider.targetFor("qwen-max");
        ChatClient.ChatClientRequestSpec request = capturingRequest();

        provider.applyThinkingOptions(request, 0, target.model());

        OpenAiChatOptions applied = lastAppliedOptions(request);

        assertThat(applied.getStreamOptions())
                .as("提前 return 会让「不开思考的对话完全没有真实用量」")
                .isNotNull();
        assertThat(applied.getStreamOptions().includeUsage())
                .as("stream_options.include_usage 是拿到真实 usage 的唯一开关")
                .isTrue();
        // extraBody 在「不思考」这一档可能整个为 null（没设过就是没设过）
        assertThat(applied.getExtraBody() == null ? Map.of() : applied.getExtraBody())
                .doesNotContainKey("thinking_budget");
    }

    @Test
    @DisplayName("模型为 null 时原样返回请求，不调 options")
    void nullModelReturnsRequestUnchanged() {
        ChatClient.ChatClientRequestSpec request = capturingRequest();

        provider.applyThinkingOptions(request, 512, null);

        verify(request, never()).options(any(ChatOptions.Builder.class));
    }

    @Test
    @DisplayName("两参重载用当前默认模型，行为与改造前一致")
    void twoArgOverloadUsesCurrentDefault() {
        ChatClient.ChatClientRequestSpec request = capturingRequest();

        provider.applyThinkingOptions(request, 128);

        assertThat(lastAppliedOptions(request).getModel()).isEqualTo("default-model");
    }

    @Test
    @DisplayName("模型选项不是 OpenAI 系时原样返回请求")
    void nonOpenAiOptionsAreLeftAlone() {
        ChatClient.ChatClientRequestSpec request = capturingRequest();
        ChatModel generic = mock(ChatModel.class);
        when(generic.getOptions()).thenReturn(mock(ChatOptions.class));

        provider.applyThinkingOptions(request, 512, generic);

        verify(request, never()).options(any(ChatOptions.Builder.class));
    }

    // ------------------------------------------------------------------ 请求替身辅助

    /**
     * 造一个会记录 {@code options(...)} 实参的请求替身。
     *
     * <p>用 Mockito 而不是手写实现：{@code ChatClientRequestSpec} 有二十多个方法，
     * 手写一份要全部实现（且每加一个默认方法就得改），而本测试只关心
     * {@code options} 被传了什么。用 mock 只需让那个方法返回自身。
     */
    private static ChatClient.ChatClientRequestSpec capturingRequest() {
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        // options(B) 是泛型方法，mock 需要显式回答「返回自身」，
        // 否则链式调用会拿到 null 并在下一步空指针
        when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
        return request;
    }

    /** 取出最后一次传给 {@code options(...)} 的构建器并 build */
    private static OpenAiChatOptions lastAppliedOptions(ChatClient.ChatClientRequestSpec request) {
        ArgumentCaptor<ChatOptions.Builder> captor = ArgumentCaptor.forClass(ChatOptions.Builder.class);
        verify(request, atLeastOnce()).options(captor.capture());
        // 关键：一定是在**真实**的 OpenAiChatOptions.Builder 上调 build()。
        // 若这里退化成 mock，build() 会返回 mock，所有对选项字段的断言都变成
        // 「对 null 断言非空」这种看起来通过、实际什么都没验证的假绿。
        ChatOptions.Builder<?> builder = captor.getValue();
        assertThat(builder)
                .as("applyThinkingOptions 必须把真实的 OpenAI 选项构建器交给请求")
                .isInstanceOf(OpenAiChatOptions.Builder.class);
        return (OpenAiChatOptions) builder.build();
    }

    // ------------------------------------------------------------------ 替身

    /** 记录被请求构造过几次、每次用的什么名字 */
    private static final class RecordingResolver implements RuntimeChatModelResolver {
        final AtomicInteger calls = new AtomicInteger();
        RuntimeException failure;

        @Override
        public ChatModel resolve(String modelName) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new FakeModel(modelName, "sk-test");
        }

        @Override
        public String defaultChatModel() {
            return "default-model";
        }
    }

    /**
     * 极简模型替身：只提供 {@code getOptions()} 里那个模型名。
     *
     * <p>不 mock {@code ChatModel} 接口：它的方法太多，用 mock 要写一堆无意义的
     * {@code when}，而本类只关心「选项里的模型名」这一个属性。
     */
    private static final class FakeModel implements ChatModel {
        private final OpenAiChatOptions options;

        FakeModel(String modelName, String apiKey) {
            this.options = OpenAiChatOptions.builder()
                    .model(modelName)
                    .temperature(0.7)
                    .build();
        }

        @Override
        public ChatOptions getOptions() {
            return options;
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException("本测试不真的调用模型");
        }
    }

    private static String modelNameOf(ChatModel model) {
        ChatOptions options = model.getOptions();
        return options instanceof OpenAiChatOptions openAi ? openAi.getModel() : null;
    }

    private static <T> ObjectProvider<T> providerOf(T item) {
        return new StaticObjectProvider<>(List.of(item));
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new StaticObjectProvider<>(List.of());
    }
}
