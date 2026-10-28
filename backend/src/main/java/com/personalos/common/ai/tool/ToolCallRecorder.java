package com.personalos.common.ai.tool;

/**
 * 工具调用结果的旁路记录器（SPI）。
 *
 * <h2>为什么是 SPI 而不是直接依赖计划模块</h2>
 * {@link ToolExecutor} 在 {@code common} 里，而「工具结果属于哪个计划步骤」是
 * {@code agent.plan} 的概念。让 common 依赖业务模块会把依赖方向倒过来，
 * 而且每加一种「想知道工具发生了什么」的功能都要改一次执行外壳。
 *
 * <p>实现方（目前是计划模块）注册成 Bean 即可被自动发现；没有实现时行为不变 ——
 * 绝大多数对话根本不涉及计划，这条旁路必须是零成本的。
 *
 * <h2>约定</h2>
 * 实现**不得抛异常**。它记录的是旁路信息，抛出来会连带把工具结果弄丢，
 * 而工具结果才是模型继续推理的依据。{@link ToolExecutor} 也做了兜底捕获。
 */
public interface ToolCallRecorder {

    /**
     * 工具执行成功。
     *
     * @param conversationId 会话
     * @param toolName       工具名
     * @param resultSummary  结果摘要（调用方已截断，不要假设是完整 JSON）
     */
    default void onToolSuccess(String conversationId, String toolName, String resultSummary) {
    }

    /**
     * 工具执行失败或被业务校验拒绝。
     *
     * @param conversationId 会话
     * @param toolName       工具名
     * @param message        失败原因
     * @return 要追加到工具结果末尾的提示（给模型看）；无需提示时返回 null
     */
    default String onToolFailure(String conversationId, String toolName, String message) {
        return null;
    }
}
