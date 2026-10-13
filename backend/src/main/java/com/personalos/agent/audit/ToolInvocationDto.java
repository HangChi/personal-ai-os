package com.personalos.agent.audit;

import java.time.Instant;

/**
 * 工具执行日志视图。
 *
 * @param operation     工具名
 * @param source        TOOL / HTTP
 * @param outcome       执行结果状态
 * @param durationMs    耗时
 * @param conversationId 所属会话，可空
 * @param createdAt     发生时间
 */
public record ToolInvocationDto(String operation,
                               String source,
                               String outcome,
                               Integer durationMs,
                               String conversationId,
                               Instant createdAt) {
}
