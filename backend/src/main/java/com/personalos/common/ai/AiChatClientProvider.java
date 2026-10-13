package com.personalos.common.ai;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
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
                                ObjectProvider<ChatClient.Builder> chatClientBuilders,
                                ChatMemory chatMemory,
                                AiProperties aiProperties) {
        ChatModel chatModel = chatModels.getIfAvailable();
        if (chatModel == null) {
            log.warn("未检测到任何 ChatModel，AI 聊天能力不可用。"
                    + "请将 AI_CHAT_PROVIDER 设为 ollama 或 openai 后重启。");
            this.chatClient = null;
            return;
        }

        // 优先使用框架自动装配的 Builder（携带 ObservationRegistry 等默认配置）；
        // 若自动配置不可用则退化为静态工厂，保证能力仍可用。
        ChatClient.Builder builder = chatClientBuilders.getIfAvailable();
        if (builder == null) {
            builder = ChatClient.builder(chatModel);
        }

        this.chatClient = builder
                .defaultSystem(aiProperties.systemPrompt())
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();

        log.info("AI 聊天能力已启用：model={}, maxMessages={}",
                chatModel.getClass().getSimpleName(), aiProperties.memory().maxMessages());
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
