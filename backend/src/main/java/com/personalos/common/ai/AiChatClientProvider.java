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
 */
@Component
public class AiChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientProvider.class);

    /** 为空表示当前没有任何可用的模型（chat 能力未启用） */
    private final ChatClient chatClient;

    /**
     * 无状态单轮客户端：**不**挂记忆 Advisor、**不**挂工具。
     *
     * <p>知识库问答用它。不能复用上面那个客户端，原因有两个：
     * <ol>
     *   <li>{@code MessageChatMemoryAdvisor} 强制要求每次请求都带 {@code conversationId} 参数，
     *       单轮问答没有会话，直接调用会抛参数校验异常；</li>
     *   <li>问答需要的是「只依据给定资料作答」，把任务工具一并暴露给它只会增加模型跑偏的机会。</li>
     * </ol>
     */
    private final ChatClient plainChatClient;

    /**
     * 底层聊天模型。留着引用不是为了「以后可能用到」——{@link #thinkingOptions(int)}
     * 需要读它的默认选项来做按请求合并（见该方法注释里「为什么必须 mutate 而不是新建」）。
     */
    private final ChatModel chatModel;

    public AiChatClientProvider(ObjectProvider<ChatModel> chatModels,
                                ObjectProvider<ToolCallingManager> toolCallingManagers,
                                ObjectProvider<ToolContributor> toolContributors,
                                ObjectProvider<ToolCallbackProvider> toolCallbackProviders,
                                ChatMemory chatMemory,
                                AiProperties aiProperties) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("未检测到任何 ChatModel，AI 聊天能力不可用。"
                    + "请将 AI_CHAT_PROVIDER 设为 ollama 或 openai 后重启。");
            this.chatClient = null;
            this.plainChatClient = null;
            this.chatModel = null;
            return;
        }

        this.chatModel = chatModel;

        this.plainChatClient = ChatClient.builder(chatModel)
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

        // 收集各业务模块贡献的工具。使用 SPI 而非直接依赖具体模块，
        // 因此 chat 模块无需知道「任务」「知识库」这些业务概念的存在。
        List<Object> tools = new ArrayList<>();
        toolContributors.forEach(contributor -> tools.addAll(contributor.toolObjects()));

        // 收集「已成型」的工具回调（阶段 6：MCP 工具经 mcp 模块的装饰层以
        // ToolCallbackProvider 形态提供）。本类不认识 MCP 这个概念——
        // 它只是多收集一类 Provider，与 @Tool 那条路径并列。
        List<ToolCallback> toolCallbacks = new ArrayList<>();
        toolCallbackProviders.forEach(provider -> toolCallbacks.addAll(List.of(provider.getToolCallbacks())));

        if (!tools.isEmpty() || !toolCallbacks.isEmpty()) {
            ToolCallingAdvisor.Builder<?> advisorBuilder = ToolCallingAdvisor.builder();
            ToolCallingManager toolCallingManager = toolCallingManagers.getIfAvailable();
            if (toolCallingManager != null) {
                advisorBuilder = advisorBuilder.toolCallingManager(toolCallingManager);
            }
            builder = builder.defaultAdvisors(advisorBuilder.build());
        }
        if (!tools.isEmpty() || !toolCallbacks.isEmpty()) {
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

        this.chatClient = builder.build();

        log.info("AI 聊天能力已启用：model={}, maxMessages={}, tools={}, toolCallbacks={}",
                chatModel.getClass().getSimpleName(), aiProperties.memory().maxMessages(),
                tools.size(), toolCallbacks.size());
    }

    /**
     * @return 当前是否已装配可用的聊天客户端
     */
    public boolean isConfigured() {
        return chatClient != null;
    }

    /**
     * 把「思考程度」应用到请求上，返回应用后的请求（不支持时原样返回）。
     *
     * <h2>为什么必须基于模型默认选项合并</h2>
     * {@code ChatClient} 请求级 {@code options()} 会**整体替换**模型的默认选项。
     * 直接 {@code OpenAiChatOptions.builder()...build()} 会把 model / temperature 等配置
     * 全部丢掉，症状是「换了个模型」而不是报错。因此这里走 {@code mutate()}：
     * 先拿到模型当前的默认选项，只改 extraBody。
     *
     * <h2>为什么把这段逻辑放在本类而不是调用方</h2>
     * {@code OpenAiChatOptions} 是 provider 专有类型。放在这里，{@code chat} 模块
     * 就不需要为了一个参数去 import 具体 provider 的类 —— 换 provider 时改动只有这一处。
     * 另外 Spring AI 的 {@code options(B)} 有形参边界 {@code <B extends ChatOptions.Builder<?>>}，
     * 静态类型写成 {@code Builder<?>} 会因捕获转换无法匹配，只能在这里用具体类型调用。
     *
     * @param request        当前请求
     * @param thinkingBudget 思考预算（token）；≤0 表示不思考，原样返回
     * @return 应用了思考参数的请求；模型非 OpenAI 系时原样返回
     */
    public ChatClient.ChatClientRequestSpec applyThinkingOptions(ChatClient.ChatClientRequestSpec request,
                                                                 int thinkingBudget) {
        if (thinkingBudget <= 0 || chatModel == null) {
            return request;
        }
        ChatOptions base = chatModel.getOptions();
        if (!(base instanceof OpenAiChatOptions openAiOptions)) {
            log.debug("当前模型选项类型不支持按请求设置思考程度：{}",
                    base == null ? "null" : base.getClass().getSimpleName());
            return request;
        }
        // DashScope 兼容模式的思考参数：enable_thinking + thinking_budget
        return request.options(openAiOptions.mutate()
                .extraBody(Map.of("enable_thinking", true, "thinking_budget", thinkingBudget)));
    }

    /**
     * 获取聊天客户端；未配置时抛出业务异常（HTTP 503 / code 50300）。
     *
     * @return 已装配 system prompt 与 ChatMemory Advisor 的聊天客户端
     */
    public ChatClient requireChatClient() {
        if (chatClient == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return chatClient;
    }

    /**
     * 获取无状态单轮客户端（不挂记忆 Advisor、不挂工具）；未配置时抛出业务异常。
     *
     * @return 用于知识库问答等单轮场景的客户端
     */
    public ChatClient requirePlainChatClient() {
        if (plainChatClient == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return plainChatClient;
    }
}
