package com.personalos.calendar.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 日程与提醒配置（前缀 {@code app.calendar}）。
 *
 * @param scanEnabled          是否启用定时扫描（测试里关掉，见 src/test/resources/application.properties）
 * @param scanIntervalMs       扫描间隔（毫秒）
 * @param scanInitialDelayMs   首次扫描的延迟（毫秒），避免与应用启动争抢
 * @param scanLookahead        扫描窗口向前看多远；窗口必须覆盖「提前量」，否则到点的提醒会漏
 * @param scanLookback         扫描窗口向后看多远；覆盖日常重启造成的停机
 * @param defaultReminderLead  任务提醒的默认提前量（日程可逐个覆盖）
 * @param maxRangeDays         区间查询的最大跨度（天），防止一次拉出全部数据
 * @param defaultQueryLimit    默认返回条数
 * @param maxQueryLimit        返回条数硬上限
 */
@ConfigurationProperties(prefix = "app.calendar")
public record CalendarProperties(
        boolean scanEnabled,
        long scanIntervalMs,
        long scanInitialDelayMs,
        Duration scanLookahead,
        Duration scanLookback,
        Duration defaultReminderLead,
        int maxRangeDays,
        int defaultQueryLimit,
        int maxQueryLimit) {

    /**
     * 提前量的上限（分钟），7 天。**单一来源**：DTO 的 {@code @Max} 与扫描窗口都取这个值。
     *
     * <p>为什么必须是常量而不是各写一个数：提前量大于扫描窗口时，提醒会晚到。
     * 曾经 DTO 写 {@code @Max(10080)}、窗口固定 24h —— 建一个「5 天后、提前 7 天提醒」的
     * 日程，要等它进入 24h 窗口才被物化，此时 {@code remind_at} 已经过去 6 天，
     * 提醒既晚到、一出现又是过期态，且没有任何报错。
     */
    public static final int MAX_REMINDER_LEAD_MINUTES = 10_080;

    private static final long DEFAULT_SCAN_INTERVAL_MS = 60_000L;
    private static final long DEFAULT_SCAN_INITIAL_DELAY_MS = 10_000L;
    private static final Duration DEFAULT_LOOKAHEAD = Duration.ofHours(24);
    private static final Duration DEFAULT_LOOKBACK = Duration.ofHours(24);
    private static final Duration DEFAULT_LEAD = Duration.ofMinutes(15);
    private static final int DEFAULT_MAX_RANGE_DAYS = 90;
    private static final int DEFAULT_QUERY_LIMIT = 100;
    private static final int MAX_QUERY_LIMIT = 500;

    public CalendarProperties {
        if (scanIntervalMs <= 0) {
            scanIntervalMs = DEFAULT_SCAN_INTERVAL_MS;
        }
        if (scanInitialDelayMs < 0) {
            scanInitialDelayMs = DEFAULT_SCAN_INITIAL_DELAY_MS;
        }
        if (scanLookahead == null || scanLookahead.isNegative() || scanLookahead.isZero()) {
            scanLookahead = DEFAULT_LOOKAHEAD;
        }
        if (scanLookback == null || scanLookback.isNegative() || scanLookback.isZero()) {
            scanLookback = DEFAULT_LOOKBACK;
        }
        if (defaultReminderLead == null || defaultReminderLead.isNegative()) {
            defaultReminderLead = DEFAULT_LEAD;
        }
        if (maxRangeDays <= 0) {
            maxRangeDays = DEFAULT_MAX_RANGE_DAYS;
        }
        if (defaultQueryLimit <= 0) {
            defaultQueryLimit = DEFAULT_QUERY_LIMIT;
        }
        if (maxQueryLimit <= 0 || maxQueryLimit > MAX_QUERY_LIMIT) {
            maxQueryLimit = MAX_QUERY_LIMIT;
        }
    }

    /**
     * 日程提醒扫描实际使用的前瞻窗口：至少覆盖最长提前量。
     *
     * <p>提醒时刻 = 发生时刻 − 提前量。若窗口比提前量短，发生时刻要到「窗口边缘」才被物化，
     * 那时提醒时刻早已过去 —— 用户收到的是一条晚到数天的提醒。
     * 因此取 {@code max(scanLookahead, MAX_REMINDER_LEAD_MINUTES)}，让「配置的窗口」与
     * 「提前量上限」不可能互相矛盾，而不是要求运维去记住两者的大小关系。
     */
    public Duration effectiveScanLookahead() {
        Duration longestLead = Duration.ofMinutes(MAX_REMINDER_LEAD_MINUTES);
        return scanLookahead.compareTo(longestLead) >= 0 ? scanLookahead : longestLead;
    }

    /**
     * 归一化区间查询跨度：越界时收敛，而不是把全部日程拉出来。
     */
    public int normalizeRangeDays(Integer requested) {
        if (requested == null || requested <= 0) {
            return Math.min(7, maxRangeDays);
        }
        return Math.min(requested, maxRangeDays);
    }

    public int normalizeLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return defaultQueryLimit;
        }
        return Math.min(requested, maxQueryLimit);
    }
}
