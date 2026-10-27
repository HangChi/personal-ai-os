package com.personalos.chat.support;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * 心跳间隔（毫秒）。
     *
     * <p>15 秒：足够密（远小于常见代理的 60 秒阈值，留了四次余量），
     * 又不至于让日志级别的流量显得多余。
     */
    private static final long HEARTBEAT_INTERVAL_MILLIS = 15_000L;

    /**
     * 心跳调度器。
     *
     * <p>不复用 Reactor 的 {@code boundedElastic}：那是给「算数据」的，线程数有限，
     * 而心跳要贯穿整个流（最长 120 秒）。占着弹性池的线程只为写一行注释帧，不划算。
     *
     * <p>直接用 JDK 的守护线程池：进程退出时无需额外关闭，也不必为一个心跳
     * 引入 Spring {@code TaskScheduler} 的生命周期管理。单用户场景下同时活跃的流
     * 通常只有一个，两个线程足以覆盖「上一个流正在收尾」的窗口。
     */
    private static final ScheduledExecutorService SCHEDULER =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * 开始为某个流心跳。
     *
     * <p>发送失败只降级为 debug 日志：客户端断开时写响应必然失败，那是正常结束路径之一，
     * 不该被当成错误刷 warn。真正的清理由调用方在终止回调里 {@link #stop}。
     *
     * @return 可取消的句柄，务必在流的每条终止路径上交给 {@link #stop}
     */
    public ScheduledFuture<?> start(SseEmitter emitter) {
        return SCHEDULER.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException ex) {
                log.debug("SSE 心跳发送失败，客户端可能已断开", ex);
            }
        }, HEARTBEAT_INTERVAL_MILLIS, HEARTBEAT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
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
