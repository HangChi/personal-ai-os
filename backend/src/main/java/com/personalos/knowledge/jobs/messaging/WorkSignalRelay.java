package com.personalos.knowledge.jobs.messaging;

import com.personalos.common.config.RuntimeRole;
import com.personalos.common.web.DatabaseCoordination;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")
public class WorkSignalRelay {
    public static final String REVIEW_QUEUE="personalos.knowledge.review";
    public static final String SEARCH_QUEUE="personalos.search.project";
    private final JdbcTemplate db;
    private final RabbitTemplate rabbit;
    private final DatabaseCoordination coordination;
    private final MeterRegistry metrics;
    private final RuntimeRole role;
    private final String indexQueue;
    private final AtomicBoolean busy=new AtomicBoolean();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("work-signal-relay").factory());
    public WorkSignalRelay(JdbcTemplate db,RabbitTemplate rabbit,DatabaseCoordination coordination,MeterRegistry metrics,
                           RuntimeRole role,@Value("${app.rabbitmq.queue:personalos.knowledge.ready}") String indexQueue) {
        this.db=db;this.rabbit=rabbit;this.coordination=coordination;this.metrics=metrics;this.role=role;this.indexQueue=indexQueue;
    }
    @Scheduled(fixedDelay=1000)
    public void dispatch() {
        if (!role.allows("api") || !busy.compareAndSet(false,true)) return;
        try { executor.submit(() -> { try { relay(); } finally { busy.set(false); } }); }
        catch (RejectedExecutionException ex) { busy.set(false); }
    }
    public void relay() {
        try (var lease=coordination.acquire("work-signal-relay",1)) {
            if (lease==null) return;
            var routes=Map.of("index",indexQueue,"review",REVIEW_QUEUE,"search",SEARCH_QUEUE);
            for (var row:db.queryForList("SELECT topic,item_id,revision FROM work_signal_outbox WHERE next_publish<=now() ORDER BY next_publish LIMIT 20")) {
                String topic=(String)row.get("topic");
                try {
                    var confirmation=new CorrelationData();
                    rabbit.convertAndSend("",routes.get(topic),row.get("item_id").toString(),confirmation);
                    if (!confirmation.getFuture().get(1500,TimeUnit.MILLISECONDS).ack() || confirmation.getReturned()!=null)
                        throw new IllegalStateException("Signal not confirmed");
                    db.update("UPDATE work_signal_outbox SET next_publish=now()+interval '30 seconds' WHERE topic=? AND item_id=? AND revision=?",
                            topic,row.get("item_id"),row.get("revision"));
                    metrics.counter("personalos.work.signals","topic",topic,"result","confirmed").increment();
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
                catch (Exception ex) {
                    metrics.counter("personalos.work.signals","topic",topic,"result","retry").increment();
                    db.update("UPDATE work_signal_outbox SET next_publish=now()+interval '5 seconds' WHERE topic=? AND item_id=? AND revision=?",
                            topic,row.get("item_id"),row.get("revision"));
                    break;
                }
            }
        }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
