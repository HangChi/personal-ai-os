package com.personalos.task.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务视图。
 *
 * @param id          任务标识
 * @param title       标题
 * @param description 描述
 * @param status      状态：TODO / DOING / DONE
 * @param priority    优先级：LOW / MEDIUM / HIGH
 * @param dueAt       截止时间，可空
 * @param completedAt 完成时间，仅已完成时非空
 * @param createdAt   创建时间
 * @param updatedAt   更新时间
 * @param overdue     是否已逾期（由服务端计算，避免各客户端各算各的）
 */
public record TaskDto(UUID id,
                      String title,
                      String description,
                      String status,
                      String priority,
                      Instant dueAt,
                      Instant completedAt,
                      Instant createdAt,
                      Instant updatedAt,
                      boolean overdue) {
}
