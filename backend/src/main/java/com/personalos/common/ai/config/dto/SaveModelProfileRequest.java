package com.personalos.common.ai.config.dto;

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
 * @param name           展示名（必填）
 * @param presetId       预设标识；null 表示自定义配置
 * @param baseUrl        端点地址；空表示回落到环境变量
 * @param protocol       协议类型；null/无法识别时按 OPENAI 处理（只影响「获取模型列表」）
 * @param apiKey         密钥；null=不改动，空串=清空，非空=覆盖
 * @param chatModel      对话模型名；空表示回落到环境变量
 * @param temperature    温度 0~2；null 表示沿用服务端默认
 * @param embeddingModel 嵌入模型名；空表示回落（改动需重启生效）
 */
public record SaveModelProfileRequest(String name,
                                      String presetId,
                                      String baseUrl,
                                      String protocol,
                                      String apiKey,
                                      String chatModel,
                                      Double temperature,
                                      String embeddingModel) {
}
