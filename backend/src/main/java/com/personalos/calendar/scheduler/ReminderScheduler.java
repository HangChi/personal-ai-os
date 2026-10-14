package com.personalos.calendar.scheduler;

import com.personalos.calendar.dto.ReminderScanResult;
import com.personalos.calendar.service.ReminderScanner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提醒扫描的定时触发。
 *
 * <h2>为什么用「周期性扫描」而不是「为每条提醒注册一个定时任务」</h2>
 * 内存里的定时任务在**重启后会全部丢失**，用户会静默地收不到提醒 ——
 * 这是最难发现的一类故障：没有任何报错，只是「没提醒」。
 * 状态放在数据库里，重启后下一轮扫描会自然地把该补的补上。
 *
 * <p>代价是提醒的精度受扫描间隔限制（默认 60 秒）。对个人日程完全够用；
 * 若将来要求秒级精度，应在扫描间隔上做文章，而不是退回内存定时任务。
 *
 * <h2>为什么要把异常吞掉并记录</h2>
 * 调度线程里的异常如果逃逸，一次数据库抖动就可能让后续调度不再发生，
 * 而「不再扫描」是静默的 —— 用户只会觉得提醒莫名其妙不来了。
 * 这里选择记录错误、让下一轮继续（fixedDelay 语义下本来也会继续，但要留下痕迹）。
 *
 * <h2>为什么可以用配置关掉</h2>
 * {@code @Scheduled} 在测试里同样会跑。若调度在集成测试中途写入 reminder，
 * 会与各用例的 {@code @BeforeEach} 清理相互干扰，产生难以复现的偶发失败。
 * 因此测试把 {@code app.calendar.scan-enabled} 置为 false，需要验证扫描的用例
 * 直接调用 {@link ReminderScanner#scan(java.time.Instant)}，时间由测试给。
 */
@Component
@ConditionalOnProperty(name = "app.calendar.scan-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderScanner scanner;
    private final Clock clock;

    public ReminderScheduler(ReminderScanner scanner, Clock clock) {
        this.scanner = scanner;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.calendar.scan-interval-ms:60000}",
            initialDelayString = "${app.calendar.scan-initial-delay-ms:10000}")
    public void scan() {
        try {
            ReminderScanResult result = scanner.scan(clock.instant());
            if (result.created() > 0) {
                log.info("定时扫描新增提醒 {} 条（window={} ~ {}）",
                        result.created(), result.windowStart(), result.windowEnd());
            }
        } catch (RuntimeException ex) {
            // 不让异常逃逸：一次失败不应该让「后续还扫不扫」变成不确定的事
            log.error("提醒扫描失败，将在下一轮重试", ex);
        }
    }
}
