package com.personalos.support;

import java.time.Duration;
import java.time.Instant;

/**
 * 时间精度比较 —— 仅供测试。
 *
 * <h2>为什么需要它</h2>
 * PostgreSQL 的 {@code TIMESTAMPTZ} 只存到**微秒**，而 JVM 的 {@link Instant#now()}
 * 精度取决于操作系统：macOS 上通常只到微秒，Linux 上能到纳秒。
 *
 * <p>后果是一类「只在 CI 挂、本地永远绿」的测试：
 * 写入前拿到的 Instant 带纳秒（如 {@code ...619591Z}），
 * 从库里读回来被四舍五入到微秒（{@code ...620Z}），
 * 两者 {@code equals} 必然为假 —— 而被测逻辑其实完全正确。
 *
 * <h2>为什么是「差值 < 1 微秒」而不是 truncatedTo(MICROS)</h2>
 * PostgreSQL 是**四舍五入**到微秒，不是截断：{@code 619591} 纳秒会变成 {@code 620} 微秒。
 * 所以先 {@code truncatedTo} 再比较依然不相等。放宽容差到「不到 1 微秒」既覆盖了
 * 存储精度的损失，又远小于任何有业务意义的间隔（最细的提醒提前量也以分钟计）。
 */
public final class Instants {

    private Instants() {
    }

    /**
     * 两个时刻在「数据库存储精度」下是否相等。
     *
     * @param left  待比较时刻，可为 null
     * @param right 待比较时刻，可为 null
     * @return 都为 null 或差值小于 1 微秒时为 true
     */
    public static boolean sameAtStoragePrecision(Instant left, Instant right) {
        if (left == null || right == null) {
            return left == right;
        }
        return Duration.between(left, right).abs().compareTo(Duration.ofNanos(1_000)) < 0;
    }

    /**
     * 解析 ISO-8601 文本后在存储精度下比较（给「比较 JSON 里的时间字符串」的场景用）。
     */
    public static boolean sameTextAtStoragePrecision(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return sameAtStoragePrecision(Instant.parse(left), Instant.parse(right));
    }
}
