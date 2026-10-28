package com.personalos.common.ai.config.dto;

/**
 * 连通性测试结果。
 *
 * <h2>为什么要 {@code latencyMs} 与 {@code replyPreview}</h2>
 * 只返回「成功 / 失败」时，用户无法区分「真的通了」与「某个中间层返回了 200」。
 * 带回真实的往返耗时与模型回显的前若干字符，等于让用户看到一次真实的模型调用
 * 确实发生过 —— 这是「配置生效」的最强证据。
 *
 * <p>{@code replyPreview} 截断而不是全文：连通性测试问的是一句固定短问，
 * 回显过长会撑破界面，也没有信息增量。
 *
 * @param ok           是否连通
 * @param latencyMs    往返耗时（毫秒）
 * @param model        实际使用的模型名
 * @param replyPreview 模型回显的前若干字符；失败时为空
 * @param error        失败原因（面向用户的可读文案）；成功时为 null
 */
public record ModelConnectionTestResult(boolean ok,
                                        long latencyMs,
                                        String model,
                                        String replyPreview,
                                        String error) {
}
