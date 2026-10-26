package com.personalos.common.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 「轮次结束后的后台任务」专用线程池（2026-10-26）。
 *
 * <p>用于滚动摘要与记忆自动沉淀：两件事都需要再调一次模型，但**不能**让用户多等 ——
 * 它们的结果只影响下一轮，与当前这一轮的回答无关。
 *
 * <h2>为什么不用公共 ForkJoinPool / 默认 @Async 执行器</h2>
 * <ul>
 *   <li>任务里是阻塞的 HTTP 调用，不该占用公共池；</li>
 *   <li>需要**有界队列 + 丢弃策略**：模型变慢时宁可少做几轮摘要，
 *       也不能让任务在内存里无限堆积（这类堆积在长会话下会变成 OOM）。</li>
 * </ul>
 *
 * <p>线程数刻意很小（1~2）：个人单用户场景，并发做摘要没有意义，
 * 反而会同时打满上游模型的速率限制。
 */
@Configuration
public class ContextTaskConfig {

    /**
     * @return 后台任务执行器；队列满时丢弃新任务（摘要/沉淀是尽力而为）
     */
    @Bean(name = "contextTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService contextTaskExecutor() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 2,
                60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(32),
                runnable -> {
                    Thread thread = new Thread(runnable, "context-post-turn-" + sequence.incrementAndGet());
                    // 守护线程：应用关闭时不阻止 JVM 退出
                    thread.setDaemon(true);
                    return thread;
                },
                // 队列满：丢弃并继续 —— 少一次摘要不会有人察觉，OOM 会
                new ThreadPoolExecutor.DiscardPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
