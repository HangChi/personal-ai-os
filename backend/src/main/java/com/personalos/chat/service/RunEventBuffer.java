package com.personalos.chat.service;

import com.personalos.chat.dto.ChatStreamEvents;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** 合并短时间内的正文增量；控制事件前先刷完正文，终态后拒绝迟到事件。 */
public final class RunEventBuffer implements BiConsumer<String, Object> {
    private final BiConsumer<String, Object> writer;
    private final ScheduledExecutorService scheduler;
    private final StringBuilder pending = new StringBuilder();
    private ScheduledFuture<?> timer;
    private boolean closed;
    private RuntimeException failure;
    public RunEventBuffer(BiConsumer<String, Object> writer, ScheduledExecutorService scheduler) {
        this.writer = writer;
        this.scheduler = scheduler;
    }
    @Override public void accept(String name, Object payload) {
        synchronized (this) {
            if (closed) return;
            if (failure != null) throw new IllegalStateException("执行事件保存失败", failure);
            if ("delta".equals(name)) {
                pending.append(((ChatStreamEvents.Delta) payload).content());
                if (pending.length() >= 2048) flush();
                else if (timer == null) timer = scheduler.schedule(this::flushSafely, 100, TimeUnit.MILLISECONDS);
                return;
            }
            flush();
            if ("done".equals(name) || "yield".equals(name) || "error".equals(name)) closed = true;
        }
        // 回调可能启动下一轮或停止执行，不能持有 buffer 锁，避免与会话锁互相等待。
        writer.accept(name, payload);
    }
    private synchronized void flushSafely() {
        try { flush(); } catch (RuntimeException ex) { failure = ex; }
    }
    private void flush() {
        if (timer != null) { timer.cancel(false); timer = null; }
        if (pending.isEmpty()) return;
        writer.accept("delta", new ChatStreamEvents.Delta(pending.toString()));
        pending.setLength(0);
    }
}
