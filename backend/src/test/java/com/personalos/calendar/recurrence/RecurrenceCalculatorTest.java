package com.personalos.calendar.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.calendar.entity.RecurrenceType;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重复规则引擎的单元测试（SC-001 / SC-002 / SC-003）。
 *
 * <p>**纯单元测试**：不启动 Spring、不连数据库、不等真实时间 —— 因此可以毫秒级重复执行。
 * 时区刻意固定为 UTC，让结果与运行机器的时区无关；夏令时那一条单独用
 * {@code America/New_York} 验证「本地时刻不漂移」这个设计主张。
 */
class RecurrenceCalculatorTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private final RecurrenceCalculator calculator = new RecurrenceCalculator(UTC);

    private static Instant utc(String iso) {
        return Instant.parse(iso);
    }

    // ------------------------------------------------------------------ NONE

    @Test
    @DisplayName("NONE：区间内只发生一次；区间在其之后则不再返回")
    void noneOccursExactlyOnce() {
        Instant startsAt = utc("2026-10-15T09:00:00Z");

        assertThat(calculator.occurrences(RecurrenceType.NONE, startsAt, null,
                utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z")))
                .containsExactly(startsAt);

        // 区间完全在它之后：不应再返回（否则一次性日程会被当成每天重复）
        assertThat(calculator.occurrences(RecurrenceType.NONE, startsAt, null,
                utc("2026-11-01T00:00:00Z"), utc("2026-11-30T00:00:00Z")))
                .isEmpty();
    }

    // ------------------------------------------------------------------ DAILY / WEEKLY

    @Test
    @DisplayName("DAILY：相邻发生恰好相差 1 天")
    void dailyStepsExactlyOneDay() {
        Instant startsAt = utc("2026-10-15T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.DAILY, startsAt, null,
                utc("2026-10-15T00:00:00Z"), utc("2026-10-18T23:59:59Z"));

        assertThat(occurrences).hasSize(4);
        assertThat(occurrences).containsExactly(
                utc("2026-10-15T09:00:00Z"), utc("2026-10-16T09:00:00Z"),
                utc("2026-10-17T09:00:00Z"), utc("2026-10-18T09:00:00Z"));
    }

    @Test
    @DisplayName("WEEKLY：相邻发生恰好相差 7 天，且时刻不变")
    void weeklyStepsExactlySevenDays() {
        Instant startsAt = utc("2026-10-15T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.WEEKLY, startsAt, null,
                utc("2026-10-01T00:00:00Z"), utc("2026-11-10T00:00:00Z"));

        assertThat(occurrences).hasSize(4);
        for (int index = 1; index < occurrences.size(); index++) {
            assertThat(occurrences.get(index).toEpochMilli() - occurrences.get(index - 1).toEpochMilli())
                    .as("第 %d 次与上一次的间隔", index)
                    .isEqualTo(7L * 24 * 3600 * 1000);
        }
    }

    // ------------------------------------------------------------------ WEEKDAYS（SC-002）

    @Test
    @DisplayName("WEEKDAYS：30 天区间内落在周末的发生时刻数为 0（SC-002）")
    void weekdaysNeverFallOnWeekend() {
        Instant startsAt = utc("2026-10-01T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.WEEKDAYS, startsAt, null,
                utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z"));

        assertThat(occurrences).as("区间跨 30 天，应产生可观次数").hasSizeGreaterThan(18);
        assertThat(occurrences)
                .as("周末不应出现任何发生时刻")
                .allSatisfy(instant -> assertThat(instant.atZone(UTC).getDayOfWeek())
                        .isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
    }

    @Test
    @DisplayName("WEEKDAYS：起点落在周五时，下一次是下周一（跳过整个周末）")
    void weekdaysSkipEntireWeekend() {
        // 2026-10-16 是周五；区间到 10-21（周三）
        Instant friday = ZonedDateTime.of(LocalDate.of(2026, 10, 16), LocalTime.of(9, 0), UTC).toInstant();

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.WEEKDAYS, friday, null,
                friday.minusSeconds(1), ZonedDateTime.of(LocalDate.of(2026, 10, 21), LocalTime.of(23, 59), UTC).toInstant());

        // 周五之后直接跳到周一 —— 中间隔了 3 天，而不是 1 天
        assertThat(occurrences).extracting(instant -> instant.atZone(UTC).getDayOfWeek())
                .containsExactly(DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY);
        assertThat(occurrences.get(1).toEpochMilli() - occurrences.get(0).toEpochMilli())
                .as("周五到周一应跨 3 天")
                .isEqualTo(3L * 24 * 3600 * 1000);
    }

    // ------------------------------------------------------------------ MONTHLY（SC-003）

    @Test
    @DisplayName("MONTHLY：跨 12 个月每月恰好一次，且当月无该日时取月末（SC-003）")
    void monthlyYieldsExactlyOnePerMonthAndClampsToMonthEnd() {
        Instant startsAt = utc("2026-01-31T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.MONTHLY, startsAt, null,
                utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"));

        assertThat(occurrences).as("12 个月每月恰好一次，不跳月").hasSize(12);
        assertThat(occurrences).extracting(instant -> instant.atZone(UTC).getMonthValue())
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        // 每月取 min(31, 当月最后一天)
        assertThat(occurrences).extracting(instant -> instant.atZone(UTC).getDayOfMonth())
                .containsExactly(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31);

        // 关键：3 月必须回到 31 日，而不是停留在 2 月被钳出来的 28 日。
        // 迭代式 plusMonths(1) 会产生 1/31 → 2/28 → 3/28 的永久偏移。
        assertThat(occurrences.get(2).atZone(UTC).getDayOfMonth()).as("3 月应回到 31 日").isEqualTo(31);
        assertThat(occurrences.get(11).atZone(UTC).getDayOfMonth()).as("12 月应回到 31 日").isEqualTo(31);
    }

    @Test
    @DisplayName("MONTHLY：闰年 2 月取 29 日")
    void monthlyUsesLeapDayInLeapYear() {
        Instant startsAt = utc("2028-01-31T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.MONTHLY, startsAt, null,
                utc("2028-02-01T00:00:00Z"), utc("2028-02-29T23:59:59Z"));

        assertThat(occurrences).hasSize(1);
        assertThat(occurrences.get(0).atZone(UTC).getDayOfMonth()).isEqualTo(29);
    }

    // ------------------------------------------------------------------ 重复截止

    @Test
    @DisplayName("重复截止是**含**端点：恰好落在截止时刻的那一次仍应产生")
    void recurrenceUntilIsInclusive() {
        Instant startsAt = utc("2026-10-15T09:00:00Z");
        Instant until = utc("2026-10-17T09:00:00Z");

        List<Instant> occurrences = calculator.occurrences(RecurrenceType.DAILY, startsAt, until,
                utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z"));

        assertThat(occurrences).containsExactly(
                utc("2026-10-15T09:00:00Z"), utc("2026-10-16T09:00:00Z"), utc("2026-10-17T09:00:00Z"));
    }

    @Test
    @DisplayName("重复截止早于首次发生 → 空结果")
    void recurrenceUntilBeforeStartYieldsNothing() {
        assertThat(calculator.occurrences(RecurrenceType.DAILY, utc("2026-10-15T09:00:00Z"),
                utc("2026-10-14T09:00:00Z"), utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z")))
                .isEmpty();
    }

    // ------------------------------------------------------------------ 纯函数性（SC-001）

    @Test
    @DisplayName("纯函数：同一输入计算两次，结果逐元素相等（SC-001）")
    void isPureFunction() {
        Instant startsAt = utc("2026-01-31T09:00:00Z");
        Instant from = utc("2026-01-01T00:00:00Z");
        Instant to = utc("2026-12-31T23:59:59Z");

        List<Instant> first = calculator.occurrences(RecurrenceType.MONTHLY, startsAt, null, from, to);
        List<Instant> second = calculator.occurrences(RecurrenceType.MONTHLY, startsAt, null, from, to);

        assertThat(first).isEqualTo(second);
    }

    // ------------------------------------------------------------------ 边界

    @Test
    @DisplayName("区间非法（起点晚于终点）→ 空结果而不是抛异常")
    void invalidRangeYieldsEmpty() {
        assertThat(calculator.occurrences(RecurrenceType.DAILY, utc("2026-10-15T09:00:00Z"), null,
                utc("2026-10-31T00:00:00Z"), utc("2026-10-01T00:00:00Z")))
                .isEmpty();
    }

    @Test
    @DisplayName("首次发生晚于区间终点 → 空结果")
    void startAfterRangeYieldsEmpty() {
        assertThat(calculator.occurrences(RecurrenceType.DAILY, utc("2026-12-15T09:00:00Z"), null,
                utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z")))
                .isEmpty();
    }

    @Test
    @DisplayName("null 重复类型按 NONE 处理，不抛异常")
    void nullTypeIsTreatedAsNone() {
        Instant startsAt = utc("2026-10-15T09:00:00Z");

        assertThat(calculator.occurrences(null, startsAt, null,
                utc("2026-10-01T00:00:00Z"), utc("2026-10-31T00:00:00Z")))
                .containsExactly(startsAt);
    }

    // ------------------------------------------------------------------ 夏令时

    @Test
    @DisplayName("跨夏令时切换时本地时刻不漂移：每天 09:00 始终是本地 09:00")
    void dailyKeepsLocalWallClockAcrossDaylightSavingTransition() {
        // 2026-03-08 是美国夏令时开始日（当天本地时间从 02:00 跳到 03:00）。
        // 若在 Instant 上简单累加 24 小时，切换后的那一次会变成本地 10:00 ——
        // 用户会发现「我的每日提醒在某天突然错了一小时」。
        ZoneId newYork = ZoneId.of("America/New_York");
        RecurrenceCalculator dstCalculator = new RecurrenceCalculator(newYork);
        Instant startsAt = ZonedDateTime.of(2026, 3, 5, 9, 0, 0, 0, newYork).toInstant();

        List<Instant> occurrences = dstCalculator.occurrences(RecurrenceType.DAILY, startsAt, null,
                startsAt.minusSeconds(1),
                ZonedDateTime.of(2026, 3, 12, 23, 59, 0, 0, newYork).toInstant());

        assertThat(occurrences).hasSize(8);
        assertThat(occurrences)
                .as("每一次都必须是本地 09:00")
                .allSatisfy(instant -> assertThat(instant.atZone(newYork).toLocalTime())
                        .isEqualTo(LocalTime.of(9, 0)));
    }
}
