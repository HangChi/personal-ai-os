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
     * <h2>为什么除了条数还要有字符预算（2026-10-26）</h2>
     * 只按条数切窗有一个致命盲区：条数与体量完全解耦。20 条各 4000 字的消息约合
     * 8 万字，足以撑爆多数模型的上下文窗口；反过来 20 条「好」「嗯」又白白浪费额度。
     * 加一层字符预算后，窗口规模有了可预期的上界，而条数继续作为「别把长会话整段读进内存」
     * 的粗筛。
     *
     * @param maxMessages     送给模型的最大消息条数，非正值会被归一为默认值
     * @param maxContextChars 送给模型的消息正文总字符预算（近似 token 预算），非正值归一为默认值
     */
    public record Memory(int maxMessages, int maxContextChars) {

        /** 默认字符预算。按中文约 1.6 字符/token 估算，12000 字 ≈ 7.5k token，留足输出空间 */
        private static final int DEFAULT_MAX_CONTEXT_CHARS = 12000;

        // 注意：这里刻意**不提供** Memory(int) 便捷构造器。
        // 2026-10-26 实测：record 一旦有两个构造器，Spring Boot 的构造器绑定会挑错那个，
        // 导致 max-messages 静默绑定失败、回落默认 20（症状是「窗口不生效」而不是启动报错）。
        // 配置类只能有一个构造器 —— 这条约束比少写一个参数的便利重要得多。

        public Memory {
            if (maxMessages <= 0) {
                maxMessages = DEFAULT_MAX_MESSAGES;
            }
            if (maxContextChars <= 0) {
                maxContextChars = DEFAULT_MAX_CONTEXT_CHARS;
            }
        }
    }
}
