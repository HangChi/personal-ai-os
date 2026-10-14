package com.personalos.common.health;

import java.time.Instant;
import java.util.Map;

/**
 * 健康状态视图。
 *
 * <p><b>阶段 0 的语义</b>：只表达「应用进程存活 + 版本可读」，数据库连通性交给
 * {@code /actuator/health}。本阶段（技术债清偿）把依赖状态补了进来：
 * {@code components} 逐个报告关键依赖，{@code status} 在**关键依赖不可用时降为 DOWN**
 * （HTTP 仍是 200——本接口是状态报告，不是熔断器；前端要能读到它才能展示）。
 *
 * @param status      整体状态：关键依赖（数据库）可用 = {@code UP}，否则 {@code DOWN}
 * @param application 应用名
 * @param version     应用版本
 * @param timestamp   生成时间（UTC）
 * @param components  依赖分量状态：{@code database} = UP/DOWN；
 *                    {@code ai} = CONFIGURED/NOT_CONFIGURED（模型未配置**不算故障**，
 *                    聊天功能不可用但应用整体健康——降级而非失败）
 */
public record HealthStatusDto(String status,
                              String application,
                              String version,
                              Instant timestamp,
                              Map<String, String> components) {
}
