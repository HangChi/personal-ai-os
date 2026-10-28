package com.personalos.common.ai.config.dto;

import java.util.UUID;

/**
 * 「获取模型列表」的请求体。
 *
 * <h2>为什么带 {@code profileId}</h2>
 * 编辑一套已有配置时，密钥框按安全约定**永不回填**，因此前端拿到的是空串。
 * 但用户点「获取模型列表」时并不想重新输一遍 key —— 传 {@code profileId}
 * 让后端用该配置已保存的密钥去探测。这也顺带解决「端点留空」的情形：
 * 端点会回落到该配置的生效值。
 *
 * <p>新建配置时 {@code profileId} 为 null，此时必须自己填端点与密钥。
 *
 * @param protocol  协议类型（OPENAI / ANTHROPIC / OLLAMA）；null 按 OPENAI 处理
 * @param baseUrl   端点地址；留空且给了 profileId 时回落到该配置的端点
 * @param apiKey    API Key；留空且给了 profileId 时回落到该配置的密钥
 * @param profileId 编辑中的配置 id；新建时为 null
 */
public record ProbeModelsRequest(String protocol, String baseUrl, String apiKey, UUID profileId) {
}
