package com.personalos.memory.dto;

/**
 * 置顶 / 取消置顶请求（V26）。
 *
 * @param pinned true 表示设为常驻（无条件注入、优先占预算）
 */
public record PinMemoryRequest(boolean pinned) {
}
