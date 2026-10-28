package com.personalos.common.ai.config.dto;

import java.util.List;

/**
 * 新建 / 更新一套模型配置的请求体。
 *
 * <h2>{@code apiKey} 的三态约定</h2>
 * 与 {@link SaveModelConfigRequest} 完全一致：{@code null} = 不改动，
 * 空串 = 清空回落，非空 = 覆盖。前端不回填明文，因此「用户没碰密钥框」
 * 必然以 {@code null} 提交 —— 把它当成「清空」会让用户每改一次模型名就丢一次密钥。
 *
 * <h2>为什么 {@code name} 在 create 与 update 里都必填</h2>
 * 更新时整体覆盖（而非 PATCH 语义），少传 name 就等于「把名字清空」，
 * 而名字是卡片唯一的辨识依据。要求必传让「漏传」变成显式错误，
 * 而不是界面上出现一张无名卡片。
 *
 * <h2>为什么既有 {@code chatModel} 又有 {@code chatModels}（V23）</h2>
 * 一个端点通常提供几十上百个模型（实测 DashScope 返回 183 个），
 * 「这套配置只属于某一个模型」与实际用法不符。现在以**清单**为准：
 * {@code chatModels} 的第一项就是默认模型，会被写进单值列。
 *
 * <p>保留 {@code chatModel} / {@code embeddingModel} 两个单值字段是为了
 * 兼容尚未更新的客户端：它们提交清单为空时，单值仍会被采纳。
 * 两者都给时以清单为准 —— 让两个来源同时生效会产生「界面显示 A、实际用 B」。
 *
 * @param name            展示名（必填）
 * @param presetId        预设标识；null 表示自定义配置
 * @param baseUrl         端点地址；空表示回落到环境变量
 * @param protocol        协议类型；null/无法识别时按 OPENAI 处理（只影响「获取模型列表」）
 * @param apiKey          密钥；null=不改动，空串=清空，非空=覆盖
 * @param chatModel       单个对话模型名（兼容旧客户端；清单非空时忽略）
 * @param temperature     温度 0~2；null 表示沿用服务端默认
 * @param embeddingModel  单个嵌入模型名（同上）
 * @param chatModels      对话模型清单；**第一项即默认模型**，null/空表示没有模型
 * @param embeddingModels 嵌入模型清单；第一项即默认，null/空表示没有模型
 */
public record SaveModelProfileRequest(String name,
                                      String presetId,
                                      String baseUrl,
                                      String protocol,
                                      String apiKey,
                                      String chatModel,
                                      Double temperature,
                                      String embeddingModel,
                                      List<String> chatModels,
                                      List<String> embeddingModels) {

    /**
     * 兼容构造器：只给单个模型名（V22 及以前的调用方与测试）。
     *
     * <p>清单为 null 时服务层会回落到 {@code chatModel} / {@code embeddingModel}，
     * 因此行为与改造前完全一致。
     */
    public SaveModelProfileRequest(String name, String presetId, String baseUrl, String protocol,
                                   String apiKey, String chatModel, Double temperature,
                                   String embeddingModel) {
        this(name, presetId, baseUrl, protocol, apiKey, chatModel, temperature, embeddingModel,
                null, null);
    }
}
