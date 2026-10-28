package com.personalos.common.ai.config.dto;

import com.personalos.common.ai.config.ModelConfigService.ConfigSource;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 一套模型配置的对外视图。
 *
 * <h2>为什么有 {@code apiKeyMasked} 而没有明文 {@code apiKey}</h2>
 * 同 {@link ModelConfigDto}：这是接口级的硬约束。用户需要知道的只有
 * 「配没配」（{@code hasApiKey}）与「是不是我上次填的那把」（末 4 位掩码）。
 *
 * <h2>为什么带 {@code sources}</h2>
 * 每套配置都可以只填一部分字段、让其余项回落到 .env。逐字段标来源，
 * 用户才能看出「这套配置的端点其实来自环境变量」——否则卡片上显示的是
 * 生效值，看起来像是这套配置自己填的，切换后找不到为何没变。
 *
 * @param id              配置 id
 * @param name            展示名
 * @param presetId        预设标识；null 表示自定义配置
 * @param baseUrl         生效的端点地址
 * @param protocol        协议类型（OPENAI / ANTHROPIC / OLLAMA），只影响「获取模型列表」
 * @param apiKeyMasked    密钥掩码（形如 {@code ****8f3a}）；未配置时为空串
 * @param hasApiKey       是否已配置密钥
 * @param chatModel       生效的对话模型名
 * @param temperature     生效的温度；null 表示沿用服务端默认
 * @param embeddingModel  生效的嵌入模型名（改动需重启）
 * @param active          是否当前生效
 * @param sortOrder       排序位
 * @param revision        配置版本；0 表示从未保存过内容
 * @param updatedAt       最后更新时间
 * @param sources         各字段的取值来源
 */
public record ModelProfileDto(UUID id,
                              String name,
                              String presetId,
                              String baseUrl,
                              String protocol,
                              String apiKeyMasked,
                              boolean hasApiKey,
                              String chatModel,
                              Double temperature,
                              String embeddingModel,
                              boolean active,
                              int sortOrder,
                              int revision,
                              Instant updatedAt,
                              Map<String, ConfigSource> sources) {
}
