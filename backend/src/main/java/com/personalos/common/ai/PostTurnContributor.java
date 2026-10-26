package com.personalos.common.ai;

/**
 * 轮次后置钩子：一轮对话**完整结束**后需要做、且不该占用本轮延迟的事。
 *
 * <h2>为什么是 SPI 而不是让 chat 直接调业务模块</h2>
 * 与 {@link PromptContributor}、{@code ToolContributor} 同一套思路：对话模块不该知道
 * 「记忆」这种业务概念的存在。否则每加一个「轮末动作」（自动沉淀、用量统计、埋点……）
 * 都要回头改对话模块，且依赖方向会变成 chat → memory 这种反向引用。
 *
 * <h2>实现约定</h2>
 * <ul>
 *   <li>实现方自己负责**异步**与**吞掉异常**：本方法在流式回调里被同步调用，
 *       任何阻塞都会拖住响应收尾，任何异常都会污染对话结果。</li>
 *   <li>不要假设助手回复一定非空（用户中途停止时可能为空），自行判断。</li>
 *   <li>可以被调用多次（同一会话的每一轮各一次），实现方需自行保证幂等或限流。</li>
 * </ul>
 */
public interface PostTurnContributor {

    /**
     * @param conversationId 会话标识
     * @param userMessage    本轮用户消息
     * @param assistantReply 本轮助手完整回复（可能为空）
     */
    void afterTurn(String conversationId, String userMessage, String assistantReply);
}
