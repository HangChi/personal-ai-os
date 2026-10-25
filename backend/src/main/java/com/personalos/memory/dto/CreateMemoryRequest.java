package com.personalos.memory.dto;

/**
 * 新增记忆请求。
 *
 * <p>与阶段 7 的标题校验同一模式：不在此加 {@code @Size} —— 注解校验的是原始长度，
 * 而业务要求按「去空白后」的字符数判定。校验唯一入口是
 * {@code MemoryService#create} 里的 {@code Texts.requireNotBlank}，
 * 避免同一字段出现两种口径（specs/008-long-term-memory/plan.md）。
 *
 * @param content 记忆原文；缺失或全空白会被拒绝
 */
public record CreateMemoryRequest(String content) {
}
