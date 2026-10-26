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
            memory = new Memory(DEFAULT_MAX_MESSAGES, 0);
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = DEFAULT_SYSTEM_PROMPT;
        }
    }

    /**
     * 上下文窗口配置。
     *
     * <p><b>重要</b>：这些上限只作用于「送给模型的消息」，不影响数据库中保存的历史
     * （见 specs/002-ai-chat/data-model.md 的读写语义约定）。
     *
     * <h2>条数与预算的分工（2026-10-26 重构）</h2>
     * 条数是**粗筛**（别把长会话整段读进内存）；真正的窗口上界是 token 预算，
     * 由 {@link ContextBudgetService} 持有、用户可选（默认 32K）。
     * 早先这里还有一个 {@code maxContextChars} 字符阈值，重构后没有消费方了 ——
     * 留着只会让人以为改它能影响窗口，因此删掉。
     *
     * @param maxMessages        送给模型的最大消息条数，非正值会被归一为默认值
     * @param contextWindowTokens 模型支持的上下文窗口（token）。qwen 系列常见 128K；
     *                           长上下文模型（如 qwen-long 的 1M）改这一个值即可
     */
    public record Memory(int maxMessages, int contextWindowTokens) {

        /**
         * 默认模型上下文窗口（token）。
         * 取 131072（128Ki）而不是 128000：前端档位按 1024 进制生成，
         * 用 1000 进制的数会让「满窗」档位被过滤掉。
         */
        private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 131_072;

        // 注意：这里刻意**不提供** Memory(int) 便捷构造器。
        // 2026-10-26 实测：record 一旦有两个构造器，Spring Boot 的构造器绑定会挑错那个，
        // 导致 max-messages 静默绑定失败、回落默认 20（症状是「窗口不生效」而不是启动报错）。
        // 配置类只能有一个构造器 —— 这条约束比少写一个参数的便利重要得多。

        public Memory {
            if (maxMessages <= 0) {
                maxMessages = DEFAULT_MAX_MESSAGES;
            }
            if (contextWindowTokens <= 0) {
                contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS;
            }
        }
    }
}
