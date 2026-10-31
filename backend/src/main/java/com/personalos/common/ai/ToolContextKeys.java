package com.personalos.common.ai;

/**
 * 工具上下文中的键名。
 *
 * <p>调用方（{@code chat}）与工具方（{@code agent}）必须就这些键名达成一致，
 * 因此放在公共模块而不是各自硬编码字符串 —— 拼错的字符串不会编译报错，
 * 只会让工具层幂等静默失效。
 */
public final class ToolContextKeys {

    /** 当前会话标识 */
    public static final String CONVERSATION_ID = "conversationId";

    /**
     * 当前对话轮次标识。
     *
     * <p>这是工具层幂等的范围边界：同一轮内「同工具 + 同参数」才算重复，
     * 跨轮次的相同调用会被正常执行（用户可能真的想建两个同名任务）。
     */
    public static final String TURN_ID = "turnId";

    public static final String EXECUTION_BUDGET = "agentExecutionBudget";

    private ToolContextKeys() {
    }
}
