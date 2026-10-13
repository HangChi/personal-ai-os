package com.personalos.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用自身的类型化配置（前缀 {@code app}）。
 *
 * <p>使用 record + 构造器绑定，避免字段注入（宪法原则 III）。
 * 由启动类上的 {@code @ConfigurationPropertiesScan} 自动注册。
 *
 * @param name    应用名，来自 {@code app.name}
 * @param version 应用版本，构建时由 Maven 资源过滤写入 {@code app.version}
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String name, String version) {
}
