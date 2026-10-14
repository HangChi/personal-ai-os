package com.personalos.calendar.recurrence;

import com.personalos.calendar.entity.RecurrenceType;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 重复规则展开：给定规则与区间，算出区间内的**发生时刻**。
 *
 * <h2>为什么是纯函数</h2>
 * 同样的输入永远得到同样的输出，没有隐藏状态、不读当前时间。因此它可以被逐元素断言
 * （SC-001），也能在任意顺序下重复执行 —— 这类逻辑一旦掺进「现在几点」，
 * 测试就只能靠等待真实时间来验证，最终结果是没人愿意跑它。
 *
 * <h2>为什么在本地时间上做加减，而不是在 Instant 上加减 24 小时</h2>
 * 「每天 09:00」指的是**本地** 09:00。若在 {@link Instant} 上累加 {@code 24h}，
 * 跨夏令时切换时本地时刻会漂移一小时（用户会发现某天的会议变成 08:00 或 10:00）。
 * 因此先把起点转成 {@link ZonedDateTime}，用 {@code plusDays}/{@code plusWeeks} 推进，
 * 再转回 {@link Instant}。
 *
 * <h2>MONTHLY 的语义（容易被误认为是笔误，故写明）</h2>
 * 「每月 31 日」在 2 月没有 31 日，本实现**取当月最后一天**（2 月 28/29 日），
 * 而不是跳过 2 月 —— 跳过会让用户整月收不到提醒且不知道为什么。
 *
 * <p><b>实现方式很关键</b>：每次都从**原始起点**按月份序号计算 {@code start.plusMonths(n)}，
 * 而不是迭代地 {@code plusMonths(1)}。因为 {@code java.time} 的 {@code plusMonths}
 * 会把超出当月的日钳到当月最后一天：
 * <ul>
 *   <li>从原始起点算：1/31 →(+1) 2/28 →(+2) <b>3/31</b> ✔ 符合「每月 31 日」的直觉</li>
 *   <li>迭代地加：1/31 → 2/28 → <b>3/28</b> ✘ 从此永久偏移到 28 号</li>
 * </ul>
 */
@Component
public class RecurrenceCalculator {

    /**
     * 迭代上限：防的是「规则写错导致死循环」这类问题把请求线程卡死。
     * 个人日程的量级远低于此值（每天一次 × 50 年 ≈ 1.8 万次）。
     */
    private static final int MAX_ITERATIONS = 20_000;

    private final ZoneId zone;

    public RecurrenceCalculator(ZoneId zone) {
        this.zone = zone;
    }

    /**
     * 计算 {@code [from, to]} 区间内的发生时刻。
     *
     * @param type   重复类型；{@code null} 视为不重复
     * @param startsAt 首次发生时间
     * @param until  重复截止（**含**）；{@code null} 表示无限
     * @param from   区间起点（含）
     * @param to     区间终点（含）
     * @return 升序的发生时刻；区间非法或无发生时返回空列表（不是 {@code null}）
     */
    public List<Instant> occurrences(RecurrenceType type, Instant startsAt, Instant until,
                                     Instant from, Instant to) {
        RecurrenceType effectiveType = type == null ? RecurrenceType.NONE : type;

        // 早退：把「不可能有结果」的情况集中在这里，后面的分支就只需关心推进逻辑
        if (from == null || to == null || from.isAfter(to)) {
            return List.of();
        }
        if (startsAt == null || startsAt.isAfter(to)) {
            return List.of();
        }
        if (until != null && startsAt.isAfter(until)) {
            return List.of();
        }

        List<Instant> sink = new ArrayList<>();
        ZonedDateTime start = startsAt.atZone(zone);

        switch (effectiveType) {
            case NONE -> {
                if (!startsAt.isBefore(from)) {
                    sink.add(startsAt);
                }
            }
            case DAILY -> collectStepped(start, 1, ChronoUnit.DAYS, until, from, to, sink);
            case WEEKLY -> collectStepped(start, 1, ChronoUnit.WEEKS, until, from, to, sink);
            case WEEKDAYS -> collectWeekdays(start, until, from, to, sink);
            case MONTHLY -> collectMonthly(start, until, from, to, sink);
        }
        return List.copyOf(sink);
    }

    private void collectStepped(ZonedDateTime start, int step, ChronoUnit unit, Instant until,
                                Instant from, Instant to, List<Instant> sink) {
        ZonedDateTime cursor = start;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Instant instant = cursor.toInstant();
            if (instant.isAfter(to) || (until != null && instant.isAfter(until))) {
                break;
            }
            if (!instant.isBefore(from)) {
                sink.add(instant);
            }
            cursor = unit == ChronoUnit.WEEKS ? cursor.plusWeeks(step) : cursor.plusDays(step);
        }
    }

    private void collectWeekdays(ZonedDateTime start, Instant until, Instant from, Instant to,
                                 List<Instant> sink) {
        ZonedDateTime cursor = start;
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Instant instant = cursor.toInstant();
            if (instant.isAfter(to) || (until != null && instant.isAfter(until))) {
                break;
            }
            if (isWeekday(cursor.getDayOfWeek()) && !instant.isBefore(from)) {
                sink.add(instant);
            }
            cursor = cursor.plusDays(1);
        }
    }

    private void collectMonthly(ZonedDateTime start, Instant until, Instant from, Instant to,
                                List<Instant> sink) {
        // 从原始起点按月份序号计算，而不是迭代累加（见类注释）
        for (int monthOffset = 0; monthOffset < MAX_ITERATIONS; monthOffset++) {
            Instant instant = start.plusMonths(monthOffset).toInstant();
            if (instant.isAfter(to) || (until != null && instant.isAfter(until))) {
                break;
            }
            if (!instant.isBefore(from)) {
                sink.add(instant);
            }
        }
    }

    private boolean isWeekday(java.time.DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case SATURDAY, SUNDAY -> false;
            default -> true;
        };
    }
}
