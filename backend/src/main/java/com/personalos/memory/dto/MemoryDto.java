package com.personalos.memory.dto;

import java.time.Instant;

/**
 * 记忆视图（对外契约）。
 *
 * <p>不直接暴露 JPA 实体（宪法原则 III）。`id` 用字符串形式，前端无需感知 UUID 类型。
 *
 * @param id           记忆标识
 * @param content      记忆原文
 * @param source       来源：{@code MANUAL}（显式保存）/ {@code AUTO}（轮末自动沉淀）。
 *                     V13 起提供，让页面能区分模型自行写入的条目
 * @param pinned       是否常驻（V26）：常驻条目无条件注入、优先占预算
 * @param supersededBy 被哪条更新的记忆取代（V26）。非空即不再注入，但条目本身保留，
 *                     用户仍能看到「这条被谁取代了」
 * @param createdAt    首次保存时间
 * @param updatedAt    最近修改时间
 * @param hitCount     累计被注入到对话上下文的次数
 * @param lastUsedAt   最近一次被注入的时间；null 表示从未被注入
 */
public record MemoryDto(String id, String content, String source, boolean pinned, String supersededBy,
                        Instant createdAt, Instant updatedAt, int hitCount, Instant lastUsedAt) {
}
