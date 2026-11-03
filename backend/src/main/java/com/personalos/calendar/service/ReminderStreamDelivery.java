package com.personalos.calendar.service;

import com.personalos.calendar.dto.ReminderDto;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 共享读取已提交的到点未读快照，每条连接只保留最新状态，不积压事件。
 * 新连接和重连均发送完整快照，数据库是恢复来源，不依赖内存事件重放。
 */
@Service
public class ReminderStreamDelivery {
    private static final int MAX_CONNECTIONS = 32;
    private final ReminderService reminders;
    private final MeterRegistry metrics;
    private final Supplier<SseEmitter> emitters;
    private final ExecutorService writers;
    private final Set<Client> clients = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    @Autowired
    public ReminderStreamDelivery(ReminderService reminders, MeterRegistry metrics) {
        this(reminders, metrics, () -> new SseEmitter(300_000L), new ThreadPoolExecutor(
                0, MAX_CONNECTIONS, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofVirtual().name("reminder-stream-", 0).factory(), new ThreadPoolExecutor.AbortPolicy()));
    }

    ReminderStreamDelivery(ReminderService reminders, MeterRegistry metrics,
                           Supplier<SseEmitter> emitters, ExecutorService writers) {
        this.reminders = reminders;
        this.metrics = metrics;
        this.emitters = emitters;
        this.writers = writers;
        metrics.gauge("personalos.reminders.stream.connections", clients, Set::size);
    }

    public synchronized SseEmitter open() {
        if (closed || clients.size() >= MAX_CONNECTIONS) {
            throw new BusinessException(ErrorCode.RATE_LIMITED, "提醒连接已满，请稍后重试");
        }
        SseEmitter emitter = emitters.get();
        Client client = new Client(emitter);
        emitter.onCompletion(() -> clients.remove(client));
        emitter.onTimeout(() -> { clients.remove(client); emitter.complete(); });
        emitter.onError(error -> clients.remove(client));
        clients.add(client);
        return emitter;
    }

    /** 无订阅者不读库；无论标签页数量多少，每轮只查询一次。 */
    @Scheduled(fixedDelayString = "${app.calendar.stream-check-interval-ms:1000}")
    public synchronized void publish() {
        if (closed || clients.isEmpty()) return;
        final List<ReminderDto> snapshot;
        try {
            snapshot = List.copyOf(reminders.list(null, true, 500));
        } catch (RuntimeException ex) {
            metrics.counter("personalos.reminders.stream.failures", "stage", "read").increment();
            return;
        }
        for (Client client : clients) {
            client.latest = snapshot;
            // 慢连接只占自己的发送槽，不能阻塞扫描、其它连接或堆积发送任务。
            if (!client.sending.compareAndSet(false, true)) continue;
            try {
                writers.execute(() -> send(client));
            } catch (RuntimeException ex) {
                client.sending.set(false);
                clients.remove(client);
                metrics.counter("personalos.reminders.stream.failures", "stage", "capacity").increment();
                client.emitter.complete();
            }
        }
    }

    private void send(Client client) {
        try {
            if (!clients.contains(client)) return;
            List<ReminderDto> snapshot = client.latest;
            if (!snapshot.equals(client.delivered)) {
                client.emitter.send(SseEmitter.event().name("reminders").data(snapshot, MediaType.APPLICATION_JSON));
                client.delivered = snapshot;
                client.lastSent = System.nanoTime();
                metrics.counter("personalos.reminders.stream.snapshots").increment();
            } else if (System.nanoTime() - client.lastSent >= TimeUnit.SECONDS.toNanos(15)) {
                client.emitter.send(SseEmitter.event().comment("ping"));
                client.lastSent = System.nanoTime();
            }
        } catch (IOException | RuntimeException ex) {
            clients.remove(client);
            metrics.counter("personalos.reminders.stream.failures", "stage", "write").increment();
            client.emitter.complete();
        } finally {
            client.sending.set(false);
        }
    }

    @PreDestroy
    public synchronized void close() {
        closed = true;
        for (Client client : clients) client.emitter.complete();
        clients.clear();
        writers.shutdownNow();
    }

    private static final class Client {
        final SseEmitter emitter;
        final AtomicBoolean sending = new AtomicBoolean();
        volatile List<ReminderDto> latest = List.of();
        List<ReminderDto> delivered;
        long lastSent;
        Client(SseEmitter emitter) { this.emitter = emitter; }
    }
}
