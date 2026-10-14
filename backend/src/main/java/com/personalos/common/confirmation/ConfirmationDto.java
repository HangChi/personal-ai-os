package com.personalos.common.confirmation;

import java.time.Instant;
import java.util.UUID;

/**
 * 待确认操作视图（下发给人类）。
 *
 * @param id            记录标识
 * @param token         一次性确认令牌
 * @param action        动作类型
 * @param summary       面向人类的可读描述
 * @param affectedCount 预计影响行数
 * @param status        当前状态
 * @param expiresAt     过期时间
 * @param createdAt     创建时间
 */
public record ConfirmationDto(UUID id,
                              String token,
                              String action,
                              String summary,
                              int affectedCount,
                              String status,
                              Instant expiresAt,
                              Instant createdAt) {
}
