/**
 * 记忆模块：长期记忆（跨会话）。
 *
 * <p>职责：用户显式要求助手记住的信息的存储、管理与注入。
 * 注入方式是实现 {@code common/ai} 的 {@code PromptContributor}，由对话模块按轮次收集 ——
 * 因此本模块不依赖 {@code chat}，也不被 {@code chat} 依赖（只经由 SPI 衔接）。
 *
 * <h2>与「会话内上下文」的分工</h2>
 * 单个会话内的消息顺序与上下文窗口由 {@code chat.memory.JpaChatMemory} 负责（阶段 1）；
 * 本模块负责**换会话也应当生效**的那一层约束（阶段 8）。
 *
 * <p>结构：{@code controller / service / repository / entity / dto / tool}，
 * 另有 {@code MemoryPromptContributor} 作为 SPI 适配器。
 */
package com.personalos.memory;
