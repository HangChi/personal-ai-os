package com.personalos.chat.service;

import com.personalos.chat.dto.ChatRunDto;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 本地通知唤醒 SSE，数据库负责游标重放；浏览器断开只关闭读取者。 */
@Component
public class RunEventDelivery {
    private final RunEventHub hub;
    private final ExecutorService readers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService flushers = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "run-event-flush");
        thread.setDaemon(true);
        return thread;
    });
    public RunEventDelivery(RunEventHub hub) { this.hub = hub; }
    public RunEventBuffer buffer(BiConsumer<String, Object> writer) { return new RunEventBuffer(writer, flushers); }

    public SseEmitter open(String id, long after, LongFunction<ChatRunDto> read) {
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        readers.submit(() -> {
            var signal = hub.subscribe(id);
            long cursor = Math.max(0, after);
            long lastHeartbeat = System.nanoTime();
            try {
                while (!closed.get()) {
                    long version = signal.version();
                    ChatRunDto run = read.apply(cursor);
                    if (!run.events().isEmpty() || !"RUNNING".equals(run.status())) {
                        if (!run.events().isEmpty()) cursor = run.events().getLast().id();
                        emitter.send(SseEmitter.event().id(Long.toString(cursor)).name("run").data(run));
                    }
                    if (!"RUNNING".equals(run.status()) && run.events().size() < 200) break;
                    if (run.events().size() == 200) continue;
                    if (System.nanoTime() - lastHeartbeat > TimeUnit.SECONDS.toNanos(15)) {
                        emitter.send(SseEmitter.event().comment("ping"));
                        lastHeartbeat = System.nanoTime();
                    }
                    signal.await(version);
                }
                emitter.complete();
            } catch (IOException | IllegalStateException ignored) {
                emitter.complete();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                emitter.complete();
            } catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            } finally { hub.unsubscribe(id, signal); }
        });
        return emitter;
    }
    @PreDestroy void close() { readers.shutdownNow(); flushers.shutdownNow(); }
}
