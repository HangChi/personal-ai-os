package com.personalos.common.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.MDC;

/**
 * 给普通 JDK 线程池补上 MDC 传递。
 *
 * <h2>为什么需要单独的包装</h2>
 * {@link MdcSchedulerHook} 只作用于 Reactor 的调度器；{@code ThreadPoolExecutor}
 * 走的是完全另一条路。轮末的摘要刷新与记忆沉淀恰恰跑在后者上 ——
 * 它们是「这一轮对话的收尾」里的日志，却因为换了线程而没有 requestId，
 * 出问题时正好缺了最该看的几条。
 *
 * <h2>为什么在提交时捕获</h2>
 * 执行时线程上的 MDC 要么是空的，要么是**上一个任务残留的**。只有提交线程
 * （也就是发起请求的那个）才知道这次任务属于哪个请求。
 *
 * <h2>实现方式</h2>
 * 用 {@link AbstractExecutorService} 代理而不是继承 {@code ThreadPoolExecutor}：
 * 后者需要重写 {@code beforeExecute}，而那已经在工作线程上执行，MDC 早已丢失。
 * 代理能干净地只改「提交」这一个环节，其余语义（有界队列、丢弃策略、关闭）原样保留。
 */
public final class MdcPropagatingExecutor extends AbstractExecutorService {

    private final ExecutorService delegate;

    public MdcPropagatingExecutor(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        delegate.execute(() -> {
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            }
            try {
                command.run();
            } finally {
                // 池化线程：不清理会让下一个任务读到上一个请求的 requestId
                MDC.clear();
            }
        });
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
