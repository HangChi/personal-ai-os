package com.personalos.memory.dto;

import java.time.Instant;

/**
 * 记忆视图（对外契约）。
 *
 * <p>不直接暴露 JPA 实体（宪法原则 III）。`id` 用字符串形式，前端无需感知 UUID 类型。
 *
 * @param id        记忆标识
 * @param content   记忆原文
 * @param createdAt 首次保存时间
 * @param updatedAt 最近修改时间
 */
public record MemoryDto(String id, String content, Instant createdAt, Instant updatedAt) {
}
