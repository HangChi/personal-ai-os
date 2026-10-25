package com.personalos.memory.dto;

/**
 * 编辑记忆请求。
 *
 * <p>与 {@link CreateMemoryRequest} 同一理由不加 {@code @Size}：
 * 长度按去空白后的字符数由 Service 层判定。
 *
 * @param content 新的记忆原文
 */
public record UpdateMemoryRequest(String content) {
}
