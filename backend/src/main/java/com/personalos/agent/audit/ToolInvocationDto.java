package com.personalos.agent.audit;

import java.time.Instant;

/**
 * 工具执行日志视图。
 *
 * @param id            记录标识（阶段 11 起返回；列表 key 与未来详情页的锚点）
 * @param operation     工具名
 * @param source        TOOL / HTTP
 * @param outcome       执行结果状态
 * @param durationMs    耗时；未真正执行时为空
 * @param conversationId 所属会话，可空
 * @param createdAt     发生时间
 */
public record ToolInvocationDto(Long id,
                               String operation,
                               String source,
                               String outcome,
                               Integer durationMs,
                               String conversationId,
                               Instant createdAt,
                               String turnId,
                               String argumentsDigest) {
}
