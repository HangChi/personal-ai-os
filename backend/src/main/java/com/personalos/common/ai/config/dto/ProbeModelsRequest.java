package com.personalos.common.ai.config.dto;

import java.util.List;
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
 * <h2>为什么还要带 {@code models}（V26）</h2>
 * 列表接口不返回窗口时，后端要逐个问 {@code /models/{name}} 才能补出窗口。
 * 但一个端点可能列出 183 个模型（DashScope 实测），逐个问会超时 + 触发限流，
 * 所以必须截断。而<b>按字母序截断是错的</b>：DashScope 里用户真正在用的
 * {@code qwen3.8-flash} 排在第 148 位、{@code qwen-max} 排第 65 位，
 * 都在上限之外 —— 「自动识别窗口」对最常用的模型恒失效。
 *
 * <p>所以让前端把<b>当前配置清单里已选的模型</b>带上来：它是用户真正关心的
 * 那一小撮（通常 2~6 个），后端优先给它们补查，剩下的名额才按字母序兜底。
 * 用户新增模型前窗口可能还是空的，此时名单里没有它，仍走字母序那条路。
 *
 * @param protocol  协议类型（OPENAI / ANTHROPIC / OLLAMA）；null 按 OPENAI 处理
 * @param baseUrl   端点地址；留空且给了 profileId 时回落到该配置的端点
 * @param apiKey    API Key；留空且给了 profileId 时回落到该配置的密钥
 * @param profileId 编辑中的配置 id；新建时为 null
 * @param models    当前配置已选中的模型名，用于优先补查窗口；可为 null 或空
 */
public record ProbeModelsRequest(
        String protocol, String baseUrl, String apiKey, UUID profileId, List<String> models) {

    /** 兼容构造器：不关心优先补查名单的调用方（测试、旧前端） */
    public ProbeModelsRequest(String protocol, String baseUrl, String apiKey, UUID profileId) {
        this(protocol, baseUrl, apiKey, profileId, null);
    }

    /** 优先补查的模型名，去掉空白与空串；永不为 null */
    public List<String> preferredModels() {
        if (models == null || models.isEmpty()) {
            return List.of();
        }
        return models.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }
}
