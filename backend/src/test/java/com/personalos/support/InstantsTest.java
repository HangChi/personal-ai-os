package com.personalos.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Instants} 的回归保护。
 *
 * <p>这个辅助类是为一类「本地绿、CI 红」的失败而写的：PostgreSQL 只存到微秒，
 * 而 Linux 上 JVM 的 {@code Instant.now()} 到纳秒。本地 macOS 时钟通常只到微秒，
 * 所以把辅助类写错也照样绿 —— 必须用它自己的测试钉住边界。
 */
class InstantsTest {

    /** 实测样本：CI 上真实出现的两个值（纳秒写入、微秒读回） */
    private static final Instant NANOS = Instant.parse("2026-10-26T06:56:26.965619591Z");
    private static final Instant MICROS = Instant.parse("2026-10-26T06:56:26.965620Z");

    @Test
    @DisplayName("纳秒值与其四舍五入到微秒后的值应视为同一时刻")
    void nanosecondValueEqualsItsMicrosecondRoundedForm() {
        assertThat(Instants.sameAtStoragePrecision(NANOS, MICROS)).isTrue();
        assertThat(Instants.sameTextAtStoragePrecision(NANOS.toString(), MICROS.toString())).isTrue();
    }

    @Test
    @DisplayName("相差 1 毫秒必须判为不同（容差不能放大到掩盖真实差异）")
    void oneMillisecondApartIsDifferent() {
        assertThat(Instants.sameAtStoragePrecision(MICROS, MICROS.plusMillis(1))).isFalse();
    }

    @Test
    @DisplayName("null 处理：同为 null 相等，一有一无不等")
    void nullHandling() {
        assertThat(Instants.sameAtStoragePrecision(null, null)).isTrue();
        assertThat(Instants.sameAtStoragePrecision(MICROS, null)).isFalse();
        assertThat(Instants.sameTextAtStoragePrecision(null, MICROS.toString())).isFalse();
    }
}
