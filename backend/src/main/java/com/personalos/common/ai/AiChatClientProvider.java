package com.personalos.common.ai;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
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

    public AiChatClientProvider(ObjectProvider<ChatModel> chatModels,
                                ObjectProvider<ToolCallingManager> toolCallingManagers,
                                ObjectProvider<ToolContributor> toolContributors,
                                ChatMemory chatMemory,
                                AiProperties aiProperties) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("未检测到任何 ChatModel，AI 聊天能力不可用。"
                    + "请将 AI_CHAT_PROVIDER 设为 ollama 或 openai 后重启。");
            this.chatClient = null;
            return;
        }

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
        if (!tools.isEmpty()) {
            ToolCallingAdvisor.Builder<?> advisorBuilder = ToolCallingAdvisor.builder();
            ToolCallingManager toolCallingManager = toolCallingManagers.getIfAvailable();
            if (toolCallingManager != null) {
                advisorBuilder = advisorBuilder.toolCallingManager(toolCallingManager);
            }
            builder = builder
                    .defaultTools(tools.toArray())
                    .defaultAdvisors(advisorBuilder.build());
        }

        this.chatClient = builder.build();

        log.info("AI 聊天能力已启用：model={}, maxMessages={}, tools={}",
                chatModel.getClass().getSimpleName(), aiProperties.memory().maxMessages(), tools.size());
    }

    /**
     * @return 当前是否已装配可用的聊天客户端
     */
    public boolean isConfigured() {
        return chatClient != null;
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
}
