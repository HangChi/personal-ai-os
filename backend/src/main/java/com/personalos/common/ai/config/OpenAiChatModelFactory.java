package com.personalos.common.ai.config;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.observation.ObservationRegistry;

/**
 * 按给定参数在**运行期**构造 {@code OpenAiChatModel}。
 *
 * <h2>为什么要自己造模型而不是只用自动配置</h2>
 * Spring AI 的自动配置在**启动期**读 {@code spring.ai.openai.*} 装配唯一一个
 * {@code ChatModel} Bean，之后无法替换。设置页要求「保存即生效」，
 * 就必须能在运行期造出新的模型实例。这里复用的是 Spring AI 自己用的那套底层入口
 * （{@link OpenAiSetup#setupSyncClient}），而不是另写一份 HTTP 调用 ——
 * 后者会把重试、超时、观测、代理这些细节全部复制一遍并逐渐偏离。
 *
 * <h2>为什么不覆盖容器里的 ChatModel Bean</h2>
 * 直接替换 Spring 容器里的 Bean 需要 {@code DefaultListableBeanFactory} 反射改
 * 单例表，且不让已注入的旧引用失效 —— 那会留下「一半旧一半新」的中间态。
 * 这里改成：{@link AiChatClientProvider} 持有一个**可替换的**模型引用，
 * 换模型就是换那个引用。ChatClient 是围绕模型重建的，因此新请求全部走新模型，
 * 已在进行的流继续用它启动时捕获的旧模型直到结束 —— 这恰好是期望语义。
 */
@Component
public class OpenAiChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatModelFactory.class);

    /**
     * 连接超时：设置页会同步等待连通性测试，太长会让「保存」按钮像卡死。
     * 10 秒足够覆盖跨境网络的正常往返，又不会让用户以为页面挂了。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** SDK 的 retries 不含首次请求；禁用隐式重试，避免与 Agent 重试叠加。 */
    private static final int MAX_RETRIES = 0;

    private final ToolCallingManager toolCallingManager;

    /**
     * 用 {@code ObjectProvider} 而不是直接注入：{@code AI_CHAT_PROVIDER=none} 时
     * 容器里没有 {@code ToolCallingManager}，直接注入会让整个应用起不来 ——
     * 而「没有模型」本来就是本项目支持的状态。取不到时传 null，
     * 由 {@code OpenAiChatModel.Builder} 使用它自己的默认实现。
     */
    public OpenAiChatModelFactory(ObjectProvider<ToolCallingManager> toolCallingManagers) {
        this.toolCallingManager = toolCallingManagers.getIfAvailable();
    }

    /**
     * 用给定参数构造一个模型实例。
     *
     * @param baseUrl     OpenAI 兼容端点根地址（不含 {@code /chat/completions}）
     * @param apiKey      API 密钥
     * @param chatModel   模型名
     * @param temperature 采样温度；null 表示沿用服务端默认
     * @param extraBody   透传给上游的额外请求体（DashScope 的 enable_thinking 等），可为空
     * @return 新的模型实例
     */
    public OpenAiChatModel create(String baseUrl, String apiKey, String chatModel,
                                  Double temperature, Map<String, Object> extraBody) {
        // ------------------------------------------------------------------
        // 参数顺序的坑（实测记录，改之前务必读完）
        //
        // 真实签名（16 个参数）：
        //
        //   (baseUrl, apiKey, credential, azureDeploymentName,
        //    azureServiceVersion, organizationId, isMicrosoftFoundry, isGitHubModels,
        //    connectionPoolMetricsPrefix, timeout, maxRetries, proxy, customHeaders,
        //    observationRegistry, meterRegistry, customizers)
        //
        // ★ 关键事实：**这个签名里没有 model 参数**。模型名只存在于
        //   OpenAiChatOptions，握手阶段完全不涉及。早先把它放在第 2 位，
        //   结果是 apiKey 为空、Authorization 头变成了 "Bearer qwen3.8-flash"，
        //   DashScope 回 401 "Incorrect API key provided" —— 而同一把密钥用
        //   curl 直连明明是通的，排查方向完全被带偏。
        //   判定方式（别再靠猜）：起一个本地 HTTP 桩接一次请求，打印
        //   Authorization 头。看到 Bearer 后面是模型名，就是这里错了。
        //
        // 其余几个已踩的坑：
        //   ① credential 与 apiKey 是**两个独立的凭据入口**，传一个即可；
        //      两者都空时才会报 "At least one credential source must be specified"。
        //   ② observationRegistry 不能传 null（内部 requireNotNull，
        //      报错信息里只有参数名 "observationRegistry"）。
        //   ③ 同步与异步客户端**都要传**给 Builder，见下方注释。
        // ------------------------------------------------------------------

        Credential credential = BearerTokenCredential.create(apiKey);

        // ------------------------------------------------------------------
        // 第三个坑（最隐蔽）：**同步与异步客户端必须都传**。
        //
        // OpenAiChatModel.Builder 同时持有 sync 与 async 两个客户端。只传 sync 时
        // build() 会走 Objects.requireNonNullElseGet，用**不含凭据的空参数**再调一次
        // OpenAiSetup.setupAsyncClient —— 于是抛出与真正原因毫无关系的
        // "At least one credential source must be specified"。
        // 堆栈里出错点是 OpenAiChatModel$Builder.build → setupAsyncClient，
        // 而不是任何与 apiKey 有关的代码，极难凭日志联想到「少传了一个客户端」。
        // ------------------------------------------------------------------
        OpenAIClient syncClient = OpenAiSetup.setupSyncClient(
                baseUrl,              // 1  baseUrl
                apiKey,               // 2  apiKey —— 不是模型名！模型名不在此签名中
                credential,           // 3  凭据（与 apiKey 二选一即可，这里都传以便轮换）
                null,                 // 4  azureDeploymentName
                null,                 // 5  azureServiceVersion
                null,                 // 6  organizationId：个人使用不涉及组织计费
                false,                // 7  isMicrosoftFoundry
                false,                // 8  isGitHubModels
                null,                 // 9  connectionPoolMetricsPrefix：不单独建连接池指标
                CONNECT_TIMEOUT,      // 10 timeout
                MAX_RETRIES,          // 11 maxRetries
                null,                 // 12 proxy：本机直连，交给系统代理选择器
                Map.<String, String>of(), // 13 customHeaders
                // 14/15 观测与指标：必须给 NOOP 而不是 null。
                //       观测由外层 ChatClient 的 Advisor 与 Micrometer 指标负责，不重复建。
                ObservationRegistry.NOOP,
                null,                 // 15 meterRegistry
                List.of());           // 16 httpClientBuilderCustomizers

        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, apiKey, credential, null, null, null,
                false, false, null, CONNECT_TIMEOUT, MAX_RETRIES, null,
                Map.<String, String>of(), ObservationRegistry.NOOP, null, List.of());

        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                .model(chatModel);
        if (temperature != null) {
            options = options.temperature(temperature);
        }
        if (extraBody != null && !extraBody.isEmpty()) {
            options = options.extraBody(extraBody);
        }

        OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .options(options.build());
        // 为 null 时不调用 setter，让 Builder 用自带的默认实现（SimpleToolCallingManager）
        if (toolCallingManager != null) {
            builder = builder.toolCallingManager(toolCallingManager);
        }
        OpenAiChatModel model = builder.build();

        // 只打端点与模型名，**绝不打印密钥**
        log.info("已按运行期配置构造聊天模型：baseUrl={}, model={}, temperature={}",
                baseUrl, chatModel, temperature == null ? "（沿用默认）" : temperature);
        return model;
    }
}
