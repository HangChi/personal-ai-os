package com.personalos.knowledge.reader;

import com.personalos.common.ai.AiChatClientProvider;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * 用视觉模型把图片转成文本。
 *
 * <h2>为什么复用 plainChatClient</h2>
 * 图片转写是**单轮、无会话、无工具**的任务。用主对话客户端会带上记忆 Advisor 与全部工具，
 * 既要求 conversationId，又给了模型一堆它根本不该用的能力（knowledge 问答也出于同样理由用 plain）。
 *
 * <h2>错误语义</h2>
 * - 没配任何模型：{@code AI_NOT_CONFIGURED}（503）→ 在这里**转成** {@code UNSUPPORTED_DOCUMENT}（40003），
 *   因为对上传者而言这是「这种文件现在处理不了」，而不是「服务器坏了」。
 * - 模型调用失败：保持 {@code AI_UPSTREAM_ERROR}（502）—— 那是上游问题，不该伪装成文件问题。
 */
@Component
public class ChatClientVisionTextExtractor implements VisionTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(ChatClientVisionTextExtractor.class);

    /** 只做转录，不做描述：描述性输出会把"图片里有什么"混进知识库，污染检索 */
    private static final String PROMPT = """
            请把这张图片里的文字逐字转录出来，尽量保留原有的分段与列表结构。
            只输出转录结果，不要解释，不要补充图片里没有的内容。
            如果图片里没有任何可读文字，只回复两个字：无文本
            """;

    private static final String NO_TEXT_MARKER = "无文本";

    private final AiChatClientProvider aiProvider;

    public ChatClientVisionTextExtractor(AiChatClientProvider aiProvider) {
        this.aiProvider = aiProvider;
    }

    @Override
    public String extract(byte[] image, String mimeType) {
        ChatClient client;
        try {
            client = aiProvider.requirePlainChatClient();
        } catch (BusinessException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "当前未配置 AI 模型，无法解析图片（请先在 .env 中配置 ollama 或 openai）");
        }

        Media media = new Media(MimeTypeUtils.parseMimeType(mimeType), new ByteArrayResource(image));
        UserMessage message = UserMessage.builder().text(PROMPT).media(media).build();

        String content = client.prompt().messages(message).call().content();
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        // 模型按提示词回复「无文本」时归一成空串，交给上层走「未提取到文本」的统一提示
        return NO_TEXT_MARKER.equals(trimmed) ? "" : trimmed;
    }
}
