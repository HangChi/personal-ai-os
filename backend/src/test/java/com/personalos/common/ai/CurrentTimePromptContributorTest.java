package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class CurrentTimePromptContributorTest {
    @Test
    void suppliesCurrentDateAndTomorrowInCalendarZoneEvenWhenClockIsUtc() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T17:30:00Z"), ZoneOffset.UTC);
        String prompt = new CurrentTimePromptContributor(clock, ZoneId.of("Asia/Shanghai")).contribute();
        assertThat(prompt).contains("2026-11-01T01:30:00+08:00", "2026-11-01 星期日",
                "Asia/Shanghai", "明天日期：2026-11-02", "不要联网校时", "带时区偏移",
                "应承接前文用户明确指定", "仍指明天", "不要仅因今天该时段已过而自行顺延日期");
    }

    @Test
    void refreshesOnEveryTurnAndHandlesYearBoundary() {
        Clock clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(
                Instant.parse("2026-12-31T15:59:00Z"), Instant.parse("2026-12-31T16:01:00Z"));
        var contributor = new CurrentTimePromptContributor(clock, ZoneId.of("Asia/Shanghai"));
        assertThat(contributor.contribute()).contains("当前日期：2026-12-31", "明天日期：2027-01-01");
        assertThat(contributor.contribute()).contains("当前日期：2027-01-01", "明天日期：2027-01-02");
    }
}
