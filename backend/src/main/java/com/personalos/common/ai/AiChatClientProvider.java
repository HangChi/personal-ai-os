package com.personalos.common.ai;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@code ChatClient} 的唯一装配点。
 *
 * <p>设计要点：模型不可用时**不抛异常**，而是把聊天能力标记为「未配置」。
 * 这样应用仍能正常启动、健康检查与其它端点不受影响，故障被限制在聊天功能内
 * （见 research.md R-06）。未配置时调用方会收到
 * {@link ErrorCode#AI_NOT_CONFIGURED}，而不是启动失败或空指针。
 *
 * <h2>为什么客户端是可整体替换的（2026-10-28，设置页热切换）</h2>
 * 此前三个字段都是 {@code final}，模型与客户端在构造期一次性定死。
 * 设置页要求「保存即生效」，就必须能在运行期换掉这三者。
 * 现在的形态是把「模型 + 两个客户端」打成一个不可变的 {@link Bundle}，
 * 整体替换引用：
 * <ul>
 *   <li>读路径无锁（{@code volatile} 引用 + 局部变量缓存），切模型的瞬间不会让请求排队；</li>
 *   <li>替换是**原子**的：请求不可能拿到「新模型配旧客户端」的中间态；</li>
 *   <li>已在进行的流不受影响 —— 它持有的是订阅那一刻的 Bundle，
 *       用旧模型跑完。中途换模型会让一条回答前后出自两个模型，
 *       而用户只会觉得「说着说着语气变了」。</li>
 * </ul>
 */
@Component
public class AiChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientProvider.class);

    /** 构造期依赖，用于运行期重建同一套 Advisor 与工具 */
    private final ChatMemory chatMemory;
    private final AiProperties aiProperties;
    private final ToolCallingManager toolCallingManager;
    private final List<Object> tools;
    private final List<ToolCallback> toolCallbacks;

    /**
     * 当前生效的「模型 + 客户端」组合。
     *
     * <p>为 {@code null} 表示当前没有任何可用模型（chat 能力未启用）。
     */
    private volatile Bundle bundle;

    public AiChatClientProvider(ObjectProvider<ChatModel> chatModels,
                                ObjectProvider<ToolCallingManager> toolCallingManagers,
                                ObjectProvider<ToolContributor> toolContributors,
                                ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                ChatMemory chatMemory,
                                AiProperties aiProperties) {
        this.chatMemory = chatMemory;
        this.aiProperties = aiProperties;
        this.toolCallingManager = toolCallingManagers.getIfAvailable();

        // 工具集合在构造期固定：它们由各业务模块的 Bean 贡献，不随模型切换而变。
        // 先收集一次存下来，重建时直接复用 —— 每次切换都重新遍历 ObjectProvider
        // 会拿到懒加载代理，可能触发意料之外的 Bean 初始化。
        List<Object> collectedTools = new ArrayList<>();
        toolContributors.forEach(contributor -> collectedTools.addAll(contributor.toolObjects()));
        List<ToolCallback> collectedCallbacks = new ArrayList<>();
        toolCallbackProviders.forEach(provider -> collectedCallbacks.addAll(List.of(provider.getToolCallbacks())));
        this.tools = List.copyOf(collectedTools);
        this.toolCallbacks = List.copyOf(collectedCallbacks);

        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("未检测到任何 ChatModel，AI 聊天能力不可用。"
                    + "请将 AI_CHAT_PROVIDER 设为 ollama 或 openai 后重启。");
            this.bundle = null;
            return;
        }

        this.bundle = buildBundle(chatModel);
        log.info("AI 聊天能力已启用：model={}, maxMessages={}, tools={}, toolCallbacks={}",
                chatModel.getClass().getSimpleName(), aiProperties.memory().maxMessages(),
                this.tools.size(), this.toolCallbacks.size());
    }

    /**
     * 用给定模型构造一整套「模型 + 带记忆与工具的客户端 + 无状态客户端」。
     *
     * <p>三个东西必须一起换：只换模型而不换客户端，客户端里仍然嵌着旧模型实例；
     * 只换会话客户端而漏了问答客户端，会出现「对话用新模型、知识库问答用旧模型」
     * ——一个只在特定页面体现、极难联想的错位。
     */
    private Bundle buildBundle(ChatModel chatModel) {
        ChatClient plain = ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.systemPrompt())
                .build();

        // 一律用静态工厂构建并**显式**装配 Advisor，而不复用自动配置的 ChatClient.Builder。
        //
        // 原因（实测）：静态工厂不会自动装配 ToolCallingAdvisor，而缺失它的症状极具误导性 ——
        // 模型正常返回工具调用、对话正常结束、日志零报错，只是**工具从不执行**。
        // 复用自动配置的 Builder 则要赌「当前配置下它是否存在」，这条不确定性不值得保留：
        // 显式装配后，行为只取决于本方法，与自动配置的启用条件无关。
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.systemPrompt())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());

        boolean hasTools = !tools.isEmpty() || !toolCallbacks.isEmpty();
        if (hasTools) {
            ToolCallingAdvisor.Builder<?> advisorBuilder = ToolCallingAdvisor.builder();
            if (toolCallingManager != null) {
                advisorBuilder = advisorBuilder.toolCallingManager(toolCallingManager);
            }
            builder = builder.defaultAdvisors(advisorBuilder.build());

            // 两类工具合成一次 defaultTools()：它接受 Object...，其中 @Tool 标注的普通对象
            // 会被包装成回调，已经是 ToolCallback 的实例则原样使用。
            //
            // 2026-10-26 改：原先对回调单独调用 defaultToolCallbacks(...)，而它已被标记
            // 「deprecated for removal」—— 留着不只是警告，Spring AI 哪天删除该重载就会编译失败。
            // 合并后仍保持「先 @Tool 对象、后现成回调」的原顺序。
            List<Object> toolArgs = new ArrayList<>(tools);
            toolArgs.addAll(toolCallbacks);
            builder = builder.defaultTools(toolArgs.toArray());
        }

        return new Bundle(chatModel, builder.build(), plain);
    }

    /**
     * 替换为新的模型实例，并重建两个客户端。
     *
     * <p>由设置页保存配置后调用。原子替换，读路径不受影响。
     *
     * @param model 新的模型实例
     */
    public void replaceModel(ChatModel model) {
        if (model == null) {
            throw new IllegalArgumentException("不能把聊天模型替换为 null");
        }
        Bundle previous = this.bundle;
        this.bundle = buildBundle(model);
        log.info("聊天模型已切换：{} → {}",
                previous == null ? "（无）" : previous.model().getClass().getSimpleName(),
                model.getClass().getSimpleName());
    }

    /**
     * @return 当前是否已装配可用的聊天客户端
     */
    public boolean isConfigured() {
        return bundle != null;
    }

    /**
     * 把「思考程度」与「流式用量上报」应用到请求上，返回应用后的请求（不支持时原样返回）。
     *
     * <h2>为什么必须基于模型默认选项合并</h2>
     * {@code ChatClient} 请求级 {@code options()} 会**整体替换**模型的默认选项。
     * 直接 {@code OpenAiChatOptions.builder()...build()} 会把 model / temperature 等配置
     * 全部丢掉，症状是「换了个模型」而不是报错。因此这里走 {@code mutate()}：
     * 先拿到模型当前的默认选项，只改需要改的字段。
     *
     * <h2>为什么把「用量上报」也放在这里（清单第 7 项）</h2>
     * 不打开 {@code stream_options.include_usage} 时，流式响应里**根本不带 usage**，
     * 于是用量统计只能全程靠字符估算。这个开关必须与思考参数一起塞进同一次
     * {@code options()} 调用 —— 调两次后一次会覆盖前一次的 mutate 结果。
     *
     * <p>注意「思考程度」为 0 时**不能提前返回**：那样会连流式用量一起关掉，
     * 而用量开关与思考无关。改造前这里是 {@code thinkingBudget <= 0} 就 return，
     * 直接搬过来会让「不开思考的对话完全没有真实用量」——一个不报错、
     * 只在统计页表现为「全是估算值」的静默失效。
     *
     * <h2>为什么把这段逻辑放在本类而不是调用方</h2>
     * {@code OpenAiChatOptions} 是 provider 专有类型。放在这里，{@code chat} 模块
     * 就不需要为了一个参数去 import 具体 provider 的类 —— 换 provider 时改动只有这一处。
     * 另外 Spring AI 的 {@code options(B)} 有形参边界 {@code <B extends ChatOptions.Builder<?>>}，
     * 静态类型写成 {@code Builder<?>} 会因捕获转换无法匹配，只能在这里用具体类型调用。
     *
     * @param request        当前请求
     * @param thinkingBudget 思考预算（token）；≤0 表示不思考，但仍会打开流式用量上报
     * @return 应用了上述参数的请求；模型非 OpenAI 系时原样返回
     */
    public ChatClient.ChatClientRequestSpec applyThinkingOptions(ChatClient.ChatClientRequestSpec request,
                                                                 int thinkingBudget) {
        Bundle current = this.bundle;
        if (current == null) {
            return request;
        }
        ChatOptions base = current.model().getOptions();
        if (!(base instanceof OpenAiChatOptions openAiOptions)) {
            log.debug("当前模型选项类型不支持按请求设置思考程度与流式用量：{}",
                    base == null ? "null" : base.getClass().getSimpleName());
            return request;
        }
        OpenAiChatOptions.Builder mutated = openAiOptions.mutate().streamUsage(true);
        if (thinkingBudget <= 0) {
            return request.options(mutated);
        }
        // DashScope 兼容模式的思考参数：enable_thinking + thinking_budget
        return request.options(mutated.extraBody(Map.of("enable_thinking", true, "thinking_budget", thinkingBudget)));
    }

    /**
     * 获取聊天客户端；未配置时抛出业务异常（HTTP 503 / code 50300）。
     *
     * @return 已装配 system prompt 与 ChatMemory Advisor 的聊天客户端
     */
    public ChatClient requireChatClient() {
        Bundle current = this.bundle;
        if (current == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return current.chatClient();
    }

    /**
     * 获取无状态单轮客户端（不挂记忆 Advisor、不挂工具）；未配置时抛出业务异常。
     *
     * @return 用于知识库问答等单轮场景的客户端
     */
    public ChatClient requirePlainChatClient() {
        Bundle current = this.bundle;
        if (current == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return current.plainChatClient();
    }

    /**
     * 当前生效的模型实例（测试与连通性检查用）。
     *
     * @return 模型；未配置时为 {@code null}
     */
    public ChatModel currentModel() {
        Bundle current = this.bundle;
        return current == null ? null : current.model();
    }

    /**
     * 一次切换要同时替换的整体。
     *
     * <p>用 record 而不是三个 volatile 字段：三个独立字段无法保证「一起换」，
     * 请求可能读到「新模型 + 旧客户端」这种组合。
     */
    private record Bundle(ChatModel model, ChatClient chatClient, ChatClient plainChatClient) {
    }
}
