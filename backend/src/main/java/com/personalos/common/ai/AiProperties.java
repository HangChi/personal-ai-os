package com.personalos.common.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 相关配置（前缀 {@code app.ai}）。
 *
 * @param memory       上下文窗口配置
 * @param systemPrompt 助手人设（作为 {@code ChatClient} 的默认 system 消息）
 */
@ConfigurationProperties(prefix = "app.ai")
public record AiProperties(Memory memory, String systemPrompt) {

    private static final int DEFAULT_MAX_MESSAGES = 20;

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 Personal AI OS 的个人助手。回答简洁、准确，默认使用中文，必要时使用列表。";

    public AiProperties {
        // 缺省时给出可用默认值，避免因漏配导致启动期 NPE
        if (memory == null) {
            memory = new Memory(0);
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
    }

    /**
     * 上下文窗口配置。
     *
     * <p><b>重要</b>：该上限只作用于「送给模型的消息条数」，
     * 不影响数据库中保存的历史（见 specs/002-ai-chat/data-model.md 的读写语义约定）。
     *
     * @param maxMessages 送给模型的最大消息条数，非正值会被归一为默认值
     */
    public record Memory(int maxMessages) {

        public Memory {
            if (maxMessages <= 0) {
                maxMessages = DEFAULT_MAX_MESSAGES;
            }
        }
    }
}
