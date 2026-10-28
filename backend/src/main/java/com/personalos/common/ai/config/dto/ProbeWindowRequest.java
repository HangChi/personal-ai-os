package com.personalos.common.ai.config.dto;

import java.util.UUID;

/**
 * 单模型查窗口请求（阶段 24）。
 *
 * <h2>为什么单开一个接口而不是复用批量探测</h2>
 * 批量探测要拉列表、按名额挑 12 个模型逐个问详情（限流与预算约束）。
 * 而「我刚加进来这个模型，它的窗口是空的」只需要问一个 —— 复用批量接口
 * 意味着重新发生 1.8 秒的往返，还可能再撞一次 429。
 *
 * <h2>为什么带 {@code profileId}</h2>
 * 与批量探测同一个理由：密钥框按安全约定永不回填，用户点这个按钮时
 * 并不想重新输一遍 key。传 {@code profileId} 让后端用已保存的密钥。
 *
 * @param protocol  协议类型（OPENAI / ANTHROPIC / OLLAMA）；null 按 OPENAI 处理
 * @param baseUrl   端点地址；留空且给了 profileId 时回落到该配置的端点
 * @param apiKey    API Key；留空且给了 profileId 时回落到该配置的密钥
 * @param profileId 配置标识，可为 null（新建配置时）
 * @param model     要查的模型名
 */
public record ProbeWindowRequest(String protocol, String baseUrl, String apiKey, UUID profileId, String model) {
}
