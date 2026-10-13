package com.personalos.chat.dto;

/**
 * SSE 事件负载定义。
 *
 * <p>与 {@code specs/002-ai-chat/contracts/chat-api.md} 的事件契约一一对应。
 * 用类型化 record 而不是裸 {@code Map}，避免字段名在实现中漂移。
 */
public final class ChatStreamEvents {

    private ChatStreamEvents() {
    }

    /** 事件 {@code start}：流建立后立即推送一次 */
    public record Start(String conversationId) {
    }

    /** 事件 {@code delta}：每个内容增量推送一次 */
    public record Delta(String content) {
    }

    /** 事件 {@code done}：正常结束时推送，携带完整回复 */
    public record Done(String conversationId, String content) {
    }

    /**
     * 事件 {@code error}：出错时推送。
     *
     * <p>只含错误码与通用文案，**不含**堆栈、类名或上游原始报文（FR-012）。
     */
    public record Error(int code, String message) {
    }
}
