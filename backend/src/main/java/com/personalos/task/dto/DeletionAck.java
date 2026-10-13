package com.personalos.task.dto;

/**
 * 删除操作的应答。
 *
 * <p>不用 {@code null} 作为响应体：删除是幂等敏感操作，回放时需要返回一个**与首次一致**
 * 的可序列化结果，`null` 无法承载「已删除」这一信息。
 *
 * @param deleted 是否已删除（命中幂等回放时同样为 true）
 */
public record DeletionAck(boolean deleted) {
}
