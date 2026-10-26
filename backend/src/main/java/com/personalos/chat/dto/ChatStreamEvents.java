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

    /**
     * 事件 {@code citations}：知识库增强开启且检索完成后推送一次（阶段 19）。
     *
     * <p>位置在 {@code start} 之后、第一个 {@code delta} 之前 —— 用户先看到
     * 「已找到 N 条依据」，再看着回答逐字生成，不会以为检索卡住了。
     *
     * <p>N=0（无命中）也推送：前端据此关闭「正在检索」状态并显示「未找到相关依据」。
     *
     * @param count      命中条数
     * @param citations  命中片段快照（编号从 1 开始，与注入系统提示的编号一致）
     */
    public record Citations(int count, java.util.List<ChatCitationDto> citations) {
    }

    /** 事件 {@code delta}：每个内容增量推送一次 */
    public record Delta(String content) {
    }

    /**
     * 事件 {@code done}：正常结束时推送，携带完整回复。
     *
     * @param promptTokens 本轮实际送给模型的上下文 token 数（系统提示 + 窗口内历史 + 本条消息）。
     *                      阶段 22 新增，供前端「上下文长度仪表盘」显示真实用量 ——
     *                      前端拿不到拼装后的系统提示，自己估算等于给用户看一个假数字。
     *                      预算与模型窗口不在这里下发：它们在选中会话时已由
     *                      {@code GET /chat/sessions/{id}/context} 取到，且可能被用户随时改
     */
    public record Done(String conversationId, String content, int promptTokens) {
    }

    /**
     * 事件 {@code error}：出错时推送。
     *
     * <p>只含错误码与通用文案，**不含**堆栈、类名或上游原始报文（FR-012）。
     */
    public record Error(int code, String message) {
    }
}
