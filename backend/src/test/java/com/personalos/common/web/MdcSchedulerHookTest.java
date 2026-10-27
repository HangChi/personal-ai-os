package com.personalos.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * 验证 MDC 能否跨 Reactor 调度线程传递。
 *
 * <p>这条能力是「流式对话的日志带 requestId」的前提：流式执行体被
 * {@code subscribeOn(boundedElastic)} 切走，MDC 是线程本地的，不显式传递就丢了。
 */
class MdcSchedulerHookTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("派发到 boundedElastic 的任务能读到提交线程的 requestId")
    void propagatesToSchedulerThread() {
        new MdcSchedulerHook().register();

        MDC.put("requestId", "abc123");
        try {
            String seenOnWorker = Flux.just(1)
                    .publishOn(Schedulers.boundedElastic())
                    .map(ignored -> MDC.get("requestId"))
                    .blockLast();

            assertThat(seenOnWorker).isEqualTo("abc123");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("调度线程执行完会清理，不会把 requestId 漏给下一个任务")
    void clearsAfterTask() throws InterruptedException {
        new MdcSchedulerHook().register();

        MDC.put("requestId", "leak-check");
        Flux.just(1).publishOn(Schedulers.boundedElastic()).blockLast();
        MDC.clear();

        // 再跑一个「提交时 MDC 为空」的任务，它不该看到上一次的值。
        // 用哨兵字符串而不是 null：Reactor 禁止 map 返回 null（会抛 NPE）
        String seen = Flux.just(1)
                .publishOn(Schedulers.boundedElastic())
                .map(ignored -> {
                    String current = MDC.get("requestId");
                    return current == null ? "<empty>" : current;
                })
                .blockLast();

        assertThat(seen).isEqualTo("<empty>");
    }
}
