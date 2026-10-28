package com.personalos.common.ai.config.dto;

import com.personalos.common.ai.config.ModelConfigService.ConfigSource;
import java.time.Instant;
import java.util.List;
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
 * @param chatModel       生效的**默认**对话模型名（= 清单第一项）
 * @param temperature     生效的温度；null 表示沿用服务端默认
 * @param embeddingModel  生效的**默认**嵌入模型名
 * @param chatModels      保存的对话模型清单（V23）。第一项即默认，可直接用于下拉
 * @param embeddingModels 保存的嵌入模型清单（V23）
 * @param audioModels     保存的语音模型清单（V24）。第一项即默认
 * @param contextWindows  模型名 → 上下文窗口（V25）。**只含已知的**：
 *                        未出现在此表的模型表示「窗口未知」，前端应提示可手填，
 *                        运行期回落到全局配置
 * @param audioModel      生效的**默认**语音模型名（= 清单第一项）；空串表示没配，
 *                        此时聊天页的语音输入回落到浏览器自带识别
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
                              List<String> chatModels,
                              List<String> embeddingModels,
                              List<String> audioModels,
                              Map<String, Integer> contextWindows,
                              String audioModel,
                              boolean active,
                              int sortOrder,
                              int revision,
                              Instant updatedAt,
                              Map<String, ConfigSource> sources) {

    /** 紧凑构造器：空映射默认成空表，前端不必判两种「没有」 */
    public ModelProfileDto {
        contextWindows = contextWindows == null ? Map.of() : Map.copyOf(contextWindows);
    }

    /**
     * 兼容构造器：不带上下文窗口（V24 及以前的调用方与测试）。
     */
    public ModelProfileDto(UUID id, String name, String presetId, String baseUrl, String protocol,
                           String apiKeyMasked, boolean hasApiKey, String chatModel,
                           Double temperature, String embeddingModel,
                           List<String> chatModels, List<String> embeddingModels,
                           List<String> audioModels, String audioModel, boolean active,
                           int sortOrder, int revision, Instant updatedAt,
                           Map<String, ConfigSource> sources) {
        this(id, name, presetId, baseUrl, protocol, apiKeyMasked, hasApiKey, chatModel,
                temperature, embeddingModel, chatModels, embeddingModels, audioModels,
                null, audioModel, active, sortOrder, revision, updatedAt, sources);
    }
}
