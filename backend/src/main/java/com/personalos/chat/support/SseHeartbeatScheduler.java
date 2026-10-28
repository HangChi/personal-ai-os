package com.personalos.chat.support;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 心跳：按固定间隔给流写注释帧，让链路「一直有字节」。
 *
 * <h2>为什么必须有心跳</h2>
 * 思考型模型在首 token 之前可能静默 60 秒以上，而链路上的每一跳都有自己的空闲判定：
 * nginx {@code proxy_read_timeout} 默认 60 秒、部分云负载均衡更短。一旦触发，
 * 连接被单方面掐断，而**服务端不会收到任何错误** —— 日志显示一切正常，
 * 实际表现却是「流在某处莫名中断」或「前端一直转圈」。
 *
 * <p>心跳把「有没有数据」与「模型有没有产出」解耦：只要连接还在，就一直在写字节。
 *
 * <h2>为什么用注释帧</h2>
 * 注释帧（{@code :ping}）在 SSE 规范里本来就被客户端忽略，因此前端无需任何改动即兼容，
 * 也不会混进 {@code start → citations → delta* → done} 的事件序列。
 *
 * <p>从 {@code ChatCompletionService} 中抽出（P2 重构）：这是一个完整且自洽的能力，
 * 与「对话怎么编排」无关，混在一起会让那个类继续变胖。
 */
@Component
public class SseHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeatScheduler.class);

    /**
     * 默认心跳间隔。
     *
     * <p>15 秒：足够密（远小于常见代理的 60 秒阈值，留了四次余量），
     * 又不至于让日志级别的流量显得多余。
     */
    private static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(15);

    /**
     * 实际心跳间隔。
     *
     * <p>做成可配置**只为了能被测试**：真实间隔 15 秒，等一个 tick 会让用例跑成分钟级，
     * 于是那些「ping 真的发出去了吗」「连续失败会自我取消吗」的断言根本写不出来，
     * 测试就退化成「句柄不为 null」这种什么都证明不了的检查。
     * 线上不需要调它，默认值即正确值。
     */
    private final long heartbeatIntervalMillis;

    @Autowired
    public SseHeartbeatScheduler(@Value("${app.chat.heartbeat-interval:15s}") Duration heartbeatInterval) {
        this.heartbeatIntervalMillis = heartbeatInterval.toMillis();
    }

    /**
     * 仅测试用：直接给毫秒间隔。
     *
     * <p>生产走上面那个带 {@code @Value} 的构造器（{@code @Autowired} 让 Spring 在
     * 两个构造器里选它）。这里不做单位换算，避免测试里再传一个 Duration 显得啰嗦。
     */
    SseHeartbeatScheduler(long heartbeatIntervalMillis) {
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    /**
     * 心跳调度器：<b>只负责计时，不负责发送</b>。
     *
     * <p>不复用 Reactor 的 {@code boundedElastic}：那是给「算数据」的，线程数有限，
     * 而心跳要贯穿整个流（最长 120 秒）。占着弹性池的线程只为写一行注释帧，不划算。
     *
     * <p><b>单线程即可</b>：定时线程只做一件事——把 ping 投递给 {@link PING_POOL}。
     * 这一层绝不能是「计时 + 发送」合一的，见 {@link PING_POOL} 的注释。
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat-timer");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 实际写 ping 的线程池。
     *
     * <h2>为什么不能在同一批线程里「计时 + 发送」</h2>
     * 原先定时线程池只有 2 个线程，且每个线程既计时又发送。问题在于
     * {@code SseEmitter.send} 会写 socket —— 客户端死连接时（TCP 已断但没发 RST），
     * 写操作可能阻塞到内核缓冲区超时。此时该线程被占住，定时任务随之停摆：
     * <b>两个死连接就能饿死全部心跳</b>，结果是「所有流一起被网关掐断」。
     * {@code cancel(false)} 也救不了 —— 不中断的话任务会一直跑下去。
     *
     * <p>所以拆成两层：定时线程只投递（这一步不会阻塞），发送在可弹性扩容的池里做。
     * 缓存池 + 上限 16：单用户同时活跃的流通常只有一两个，16 个槽位足以覆盖
     * 「若干死连接正在超时」的窗口，而不会无限膨胀。
     */
    private static final ExecutorService PING_POOL =
            new ThreadPoolExecutor(2, 16, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, "sse-heartbeat-send");
                        thread.setDaemon(true);
                        return thread;
                    },
                    // 池满时丢弃这次 ping 而不是抛异常：少一次心跳只是延迟被网关发现，
                    // 而抛异常会让定时任务直接终止
                    new ThreadPoolExecutor.DiscardPolicy());

    /**
     * 连续失败多少次后自我取消。
     *
     * <p>客户端断开后每次 ping 都失败，若只靠调用方在终止回调里 {@code stop}，
     * 任何一条终止路径漏掉都会让心跳永远跑在这个已死的 emitter 上。
     * 连续两次失败基本可以判定连接已死（间隔 15 秒，一次可能是瞬时错误）。
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 2;

    /**
     * 开始为某个流心跳。
     *
     * <p>发送失败只降级为 debug 日志：客户端断开时写响应必然失败，那是正常结束路径之一，
     * 不该被当成错误刷 warn。连续失败达阈值则自我取消，不依赖调用方是否正确清理。
     *
     * @return 可取消的句柄，务必在流的每条终止路径上交给 {@link #stop}
     */
    public ScheduledFuture<?> start(SseEmitter emitter) {
        AtomicInteger failures = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> self = new AtomicReference<>();
        ScheduledFuture<?> future = SCHEDULER.scheduleAtFixedRate(() -> {
            // 校验 future 是否已被取消：DiscardPolicy 会在池满时静默丢任务，
            // 这里不做额外判断也不会出错，但提前返回能省掉一次无用的投递
            ScheduledFuture<?> current = self.get();
            if (current != null && current.isCancelled()) {
                return;
            }
            PING_POOL.execute(() -> {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                    failures.set(0);
                } catch (IOException | IllegalStateException ex) {
                    log.debug("SSE 心跳发送失败，客户端可能已断开", ex);
                    if (failures.incrementAndGet() >= MAX_CONSECUTIVE_FAILURES) {
                        ScheduledFuture<?> handle = self.get();
                        if (handle != null) {
                            handle.cancel(false);
                            log.debug("SSE 心跳连续失败 {} 次，停止该流的心跳",
                                    MAX_CONSECUTIVE_FAILURES);
                        }
                    }
                }
            });
        }, heartbeatIntervalMillis, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        self.set(future);
        return future;
    }

    /** 停止心跳；null 与重复取消都视为正常（多条终止路径可能同时触发）。 */
    public void stop(ScheduledFuture<?> heartbeat) {
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    /**
     * 在 {@code delay} 之后执行一次任务，用于**自己实现流超时**。
     *
     * <h2>为什么不用 SseEmitter 自带的超时</h2>
     * {@code SseEmitter} 的 timeout 是 servlet 异步请求的超时。它一触发，容器就把这个
     * 异步请求判为结束 —— 此时在 {@code onTimeout} 回调里再 {@code send()} 一个 error 帧，
     * 写出去的内容**到不了客户端**（实测：回调确实执行了、send 也没抛异常，但客户端只看到
     * 到一半就 EOF）。而那正是用户最需要一句解释的时刻。
     *
     * <p>所以超时由我们自己在连接仍然活着时触发：先发 error 帧、再 complete。
     * emitters 自带的 timeout 留作兜底（比这里稍长），防止我们的任务因故没跑。
     */
    public ScheduledFuture<?> scheduleOnce(Runnable task, long delayMillis) {
        return SCHEDULER.schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }
}
