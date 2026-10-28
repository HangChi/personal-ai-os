package com.personalos.chat.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 心跳调度器（2026-10-27 拆分定时与发送后新增）。
 *
 * <p>断言针对的是**改动本身声称解决的两个问题**，不是「句柄不为 null」这种
 * 改不改都成立的性质：
 *
 * <ol>
 *   <li>ping 真的被发出去了（拆分后定时线程只投递，投递链路断了就再没有心跳）</li>
 *   <li>连续失败会自我取消（不依赖调用方在终止回调里记得 stop）</li>
 * </ol>
 *
 * <p>间隔通过包内构造器压到几十毫秒，否则 15 秒一个 tick 只能写成「等不起，跳过」——
 * 那正是这一版之前的写法，也是它什么都证明不了的原因。
 */
class SseHeartbeatSchedulerTest {

    /** 压到 20ms：几十毫秒内能看到多个 tick，用例仍在百毫秒级跑完 */
    private static final long FAST_INTERVAL_MILLIS = 20L;

    @Test
    @DisplayName("正常流：ping 会被真正发出（拆分后投递链路没断）")
    void sendsPingsToLiveEmitter() throws Exception {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(FAST_INTERVAL_MILLIS);
        RecordingEmitter emitter = new RecordingEmitter();

        var handle = scheduler.start(emitter);
        try {
            assertThat(emitter.awaitPings(2, 5, TimeUnit.SECONDS))
                    .as("至少两个 tick 应产生两次 ping；为 0 说明定时线程没有把任务投递到发送池")
                    .isTrue();
        } finally {
            scheduler.stop(handle);
        }
    }

    @Test
    @DisplayName("stop 之后不再发 ping（多条终止路径交叠时依赖这一点）")
    void noPingsAfterStop() throws Exception {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(FAST_INTERVAL_MILLIS);
        RecordingEmitter emitter = new RecordingEmitter();

        var handle = scheduler.start(emitter);
        assertThat(emitter.awaitPings(1, 5, TimeUnit.SECONDS)).as("停止前应有心跳").isTrue();
        scheduler.stop(handle);

        // 给已经投递出去、可能正在发送的那一次留出落点，再取样作为基线
        Thread.sleep(FAST_INTERVAL_MILLIS * 3);
        int afterStop = emitter.pingCount();
        Thread.sleep(FAST_INTERVAL_MILLIS * 10);

        assertThat(emitter.pingCount())
                .as("stop 之后不应再有新心跳；仍在增长说明 cancel 没能停下来")
                .isEqualTo(afterStop);
    }

    @Test
    @DisplayName("连续失败自我取消：死连接不会让心跳永远跑下去")
    void selfCancelsAfterConsecutiveFailures() throws Exception {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(FAST_INTERVAL_MILLIS);
        FailingEmitter emitter = new FailingEmitter();

        var handle = scheduler.start(emitter);
        try {
            // 阈值是 2 次连续失败，所以总共只有 2 次尝试 —— 这里不能等更多，
            // 否则就是在等一个自我取消之后不可能再发生的事件（我第一版就是这么写错的）
            assertThat(emitter.awaitAttempts(2, 5, TimeUnit.SECONDS))
                    .as("发不出去的 ping 也应持续被尝试，否则失败计数根本不会推进")
                    .isTrue();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!handle.isCancelled() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(handle.isCancelled())
                    .as("连续失败达阈值后必须自我取消，不能只依赖调用方 stop —— "
                            + "任何一条终止路径漏掉清理，心跳就会永远跑在这个死 emitter 上")
                    .isTrue();

            int afterCancel = emitter.attemptCount();
            Thread.sleep(FAST_INTERVAL_MILLIS * 10);
            assertThat(emitter.attemptCount())
                    .as("自我取消后不应再尝试发送")
                    .isEqualTo(afterCancel);
        } finally {
            scheduler.stop(handle);
        }
    }

    @Test
    @DisplayName("stop 对 null 与重复调用都安全")
    void stopIsIdempotent() {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(FAST_INTERVAL_MILLIS);

        scheduler.stop(null);

        RecordingEmitter emitter = new RecordingEmitter();
        var handle = scheduler.start(emitter);
        scheduler.stop(handle);
        scheduler.stop(handle);
        assertThat(handle.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("scheduleOnce 到点执行，用于流超时看门狗")
    void scheduleOnceRunsTask() throws Exception {
        SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(FAST_INTERVAL_MILLIS);
        CountDownLatch latch = new CountDownLatch(1);

        var handle = scheduler.scheduleOnce(latch::countDown, 10);
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS))
                    .as("看门狗任务必须在延迟后执行，否则流永远不会超时")
                    .isTrue();
        } finally {
            handle.cancel(false);
        }
    }

    /** 记录 send 次数；不真正写网络，避免测试依赖容器 */
    private static class RecordingEmitter extends SseEmitter {
        private final AtomicInteger pings = new AtomicInteger();

        /**
         * 声明 {@code throws IOException} 是为了让 {@link FailingEmitter} 能覆写它
         * （Java 不允许子类在重写时**新增**受检异常）。许多路径下这个方法本来就会抛，
         * 正常实现只是不抛而已。
         */
        @Override
        public void send(SseEventBuilder builder) throws IOException {
            pings.incrementAndGet();
        }

        int pingCount() {
            return pings.get();
        }

        boolean awaitPings(int target, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (pings.get() >= target) {
                    return true;
                }
                Thread.sleep(5);
            }
            return false;
        }
    }

    /** 每次 send 都抛，模拟「TCP 已断但没发 RST」的死连接 */
    private static final class FailingEmitter extends RecordingEmitter {
        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            attempts.incrementAndGet();
            throw new IOException("模拟死连接");
        }

        int attemptCount() {
            return attempts.get();
        }

        boolean awaitAttempts(int target, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (System.nanoTime() < deadline) {
                if (attempts.get() >= target) {
                    return true;
                }
                Thread.sleep(5);
            }
            return false;
        }
    }
}
