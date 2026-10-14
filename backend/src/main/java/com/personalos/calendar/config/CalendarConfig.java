package com.personalos.calendar.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 日程模块配置。
 */
@Configuration
@EnableScheduling
public class CalendarConfig {

    /**
     * 日历计算使用的时区。
     *
     * <p>暴露成 Bean 而不是在代码里直接调 {@code ZoneId.systemDefault()}：
     * 重复规则的计算结果依赖时区（跨夏令时的「每天 09:00」在新旧时区下是不同的瞬时时刻），
     * 因此单测必须能注入一个固定时区，否则测试结果会随运行机器而变。
     */
    @Bean
    public ZoneId calendarZoneId() {
        return ZoneId.systemDefault();
    }

    /**
     * 时间源。
     *
     * <p>注入 {@link Clock} 而不是到处调 {@code Instant.now()}：这样「到点提醒」这类逻辑
     * 才能在测试里被推进到任意时刻验证，而不必真的等到那个时刻。
     * 一个直接调 {@code Instant.now()} 的调度器，测试只能靠 {@code Thread.sleep} ——
     * 最终结果是没人愿意跑它。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
