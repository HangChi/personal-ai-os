package com.personalos.common.ai.config.dto;

import com.personalos.common.ai.config.ModelConfigService.ConfigSource;
import java.time.Instant;
import java.util.Map;

/**
 * 模型配置的对外视图（设置页读取用）。
 *
 * <h2>为什么没有明文 {@code apiKey} 字段</h2>
 * 这是本接口唯一的安全边界：一旦响应体里出现明文密钥，它会同时存在于
 * 浏览器内存、开发者工具的网络面板、以及任何一层可能存在的访问日志里。
 * 用户需要知道的只是「配没配」和「是不是我上次填的那把」，
 * 前者由 {@code hasApiKey} 回答，后者由末 4 位掩码回答。二者都够用，明文不够安全性。
 *
 * <h2>为什么带 {@code sources}</h2>
 * 「设置了却没生效」是这类配置页最常见的困惑。逐字段标明来源
 * （设置页 / 环境变量 / 内置默认），用户就能自己看出「原来这项是被环境变量覆盖了」，
 * 而不必去翻代码和 .env。
 *
 * @param baseUrl       生效的端点地址
 * @param apiKeyMasked  密钥掩码（形如 {@code ****8f3a}）；未配置时为空串
 * @param hasApiKey     是否已配置密钥
 * @param chatModel     生效的对话模型名
 * @param temperature   生效的温度；null 表示沿用服务端默认
 * @param embeddingModel 生效的嵌入模型名（改动需重启）
 * @param revision      数据库配置版本；null 表示从未在设置页保存过
 * @param updatedAt     最后一次保存时间；null 表示从未保存过
 * @param sources       各字段的取值来源
 * @param hotReloadable  是否支持热切换（固定 true，保留字段是为了让前端不必硬编码这个事实）
 * @param embeddingHotReloadable 嵌入模型是否支持热切换（固定 false，见 {@code embeddingModel} 注释）
 */
public record ModelConfigDto(String baseUrl,
                             String apiKeyMasked,
                             boolean hasApiKey,
                             String chatModel,
                             Double temperature,
                             String embeddingModel,
                             Integer revision,
                             Instant updatedAt,
                             Map<String, ConfigSource> sources,
                             boolean hotReloadable,
                             boolean embeddingHotReloadable) {
}
