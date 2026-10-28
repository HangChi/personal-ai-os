package com.personalos.common.ai.config.dto;

import java.util.List;
import java.util.UUID;

/**
 * 调整配置顺序的请求体。
 *
 * <p>传入的是**期望的完整顺序**（id 数组），而不是「把 A 移到 B 前面」之类的
 * 相对指令：相对指令在前端拖拽有并发、失败重试时会累积成错乱顺序，
 * 而绝对顺序是幂等的 —— 同一个数组提交两次结果一样。
 *
 * @param ids 期望顺序的配置 id；未列出的配置排到末尾并按原有顺序保持稳定
 */
public record ReorderModelProfilesRequest(List<UUID> ids) {
}
