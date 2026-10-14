package com.personalos.calendar.dto;

import java.time.Instant;

/**
 * 一次扫描的结果。
 *
 * <p>{@code skipped} 不是「出错」：它表示该「来源 + 发生时刻」已经有提醒了，
 * 因而被唯一约束挡下。这个数字是幂等的**可观察证据** —— 反复扫描时
 * {@code created} 会归零而 {@code skipped} 增长，两者之和恒等于窗口内的发生次数。
 *
 * @param windowStart 扫描窗口起点
 * @param windowEnd   扫描窗口终点
 * @param scannedAt   本次扫描使用的「现在」
 * @param created     新增的提醒条数
 * @param skipped     因已存在而被忽略的条数
 * @param pruned      清理掉的孤儿提醒条数（来源已不存在）
 */
public record ReminderScanResult(Instant windowStart,
                                 Instant windowEnd,
                                 Instant scannedAt,
                                 int created,
                                 int skipped,
                                 int pruned) {
}
