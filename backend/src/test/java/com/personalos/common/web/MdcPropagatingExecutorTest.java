package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link MdcPropagatingExecutor} 的传递与清理行为。
 *
 * <p>用单线程池：多线程下无法确定第二次提交落在哪根线程上，「是否残留」就测不准了。
 */
class MdcPropagatingExecutorTest {

    private MdcPropagatingExecutor executor;

    @AfterEach
    void shutdown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("提交时的 MDC 会带到执行线程，执行完不残留")
    void propagatesAndClears() throws InterruptedException {
        executor = new MdcPropagatingExecutor(Executors.newSingleThreadExecutor());

        MDC.put("requestId", "trace-42");
        try {
            assertThat(runAndRead(executor, "第一次")).as("提交时的值应传到执行线程").isEqualTo("trace-42");
        } finally {
            MDC.clear();
        }

        // 第二次提交发生在「提交线程没有 MDC」的情况下，
        // 若上一次没清理干净，这里就会读到一个不属于自己的 requestId
        assertThat(runAndRead(executor, "第二次")).as("执行完必须清理，否则串请求").isEqualTo("<empty>");
    }

    /** 提交一个读 MDC 的任务并等它完成 */
    private String runAndRead(MdcPropagatingExecutor target, String label) throws InterruptedException {
        AtomicReference<String> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        target.execute(() -> {
            String current = MDC.get("requestId");
            seen.set(current == null ? "<empty>" : current);
            done.countDown();
        });
        assertThat(done.await(2, TimeUnit.SECONDS)).as("%s 应在 2 秒内完成", label).isTrue();
        return seen.get();
    }

    @Test
    @DisplayName("包装后仍可正常关闭（destroyMethod 依赖这一点）")
    void delegatesLifecycle() {
        ExecutorService raw = Executors.newSingleThreadExecutor();
        executor = new MdcPropagatingExecutor(raw);

        executor.shutdown();

        assertThat(executor.isShutdown()).isTrue();
        assertThat(raw.isShutdown()).isTrue();
    }
}
