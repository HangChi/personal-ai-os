package com.personalos.common.health;

import java.time.Instant;

/**
 * 健康状态视图。
 *
 * @param status      状态标识，阶段 0 恒为 {@code UP}（进程存活即视为 UP）
 * @param application 应用名
 * @param version     应用版本
 * @param timestamp   生成时间（UTC）
 */
public record HealthStatusDto(String status, String application, String version, Instant timestamp) {
}
