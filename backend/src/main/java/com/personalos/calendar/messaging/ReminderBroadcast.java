package com.personalos.calendar.messaging;

import com.personalos.calendar.service.ReminderService;
import com.personalos.calendar.service.ReminderStreamDelivery;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.DatabaseCoordination;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")
public class ReminderBroadcast {
    public static final String EXCHANGE="personalos.reminders.changed";

    @Bean FanoutExchange reminderExchange() { return new FanoutExchange(EXCHANGE,true,false); }

    @Bean Publisher reminderPublisher(RuntimeRole role, DatabaseCoordination coordination, ReminderService reminders,
                                      JdbcTemplate db, JsonCodec json, RabbitTemplate rabbit, MeterRegistry meters) {
        return new Publisher(role,coordination,reminders,db,json,rabbit,meters);
    }

    @Configuration(proxyBeanMethods=false)
    @ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")
    @ConditionalOnExpression("'${app.runtime.role:combined}' == 'api' || '${app.runtime.role:combined}' == 'combined'")
    static class Subscription {
        @Bean Declarables reminderSubscription(FanoutExchange exchange) {
            var queue=new AnonymousQueue();
            queue.addArgument("x-max-length",10);
            return new Declarables(queue,BindingBuilder.bind(queue).to(exchange));
        }

        @Bean SimpleMessageListenerContainer reminderSubscriber(CachingConnectionFactory factory,
                @org.springframework.beans.factory.annotation.Qualifier("reminderSubscription") Declarables topology,
                ReminderStreamDelivery streams,MeterRegistry meters) {
            var queue=topology.getDeclarablesByType(Queue.class).getFirst();
            var listener=new SimpleMessageListenerContainer(factory);
            listener.setQueues(queue);
            listener.setPrefetchCount(1);
            listener.setMissingQueuesFatal(false);
            listener.setDefaultRequeueRejected(false);
            listener.setMessageListener((MessageListener) message -> {
                meters.counter("personalos.reminders.broadcast.received").increment();
                streams.publish();
            });
            return listener;
        }
    }

    public static class Publisher {
        private final RuntimeRole role;
        private final DatabaseCoordination coordination;
        private final ReminderService reminders;
        private final JdbcTemplate db;
        private final JsonCodec json;
        private final RabbitTemplate rabbit;
        private final MeterRegistry meters;
        private final AtomicBoolean busy=new AtomicBoolean();
        private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofVirtual().name("reminder-broadcast").factory());

        Publisher(RuntimeRole role,DatabaseCoordination coordination,ReminderService reminders,JdbcTemplate db,
                  JsonCodec json,RabbitTemplate rabbit,MeterRegistry meters) {
            this.role=role;this.coordination=coordination;this.reminders=reminders;this.db=db;
            this.json=json;this.rabbit=rabbit;this.meters=meters;
        }

        @Scheduled(fixedDelay=1000)
        public void dispatch() {
            if (!role.allows("delivery") || !busy.compareAndSet(false,true)) return;
            try { executor.execute(() -> { try { publish(); } finally { busy.set(false); } }); }
            catch (RejectedExecutionException ex) { busy.set(false); }
        }

        private void publish() {
            try (var lease=coordination.acquire("reminder-broadcast",1)) {
                if (lease==null) return;
                String digest=JsonCodec.sha256Hex(json.toJson(reminders.list(null,true,500)));
                if (db.queryForList("SELECT id FROM reminder_broadcast_state WHERE id=1 AND digest=?",digest).size()==1) return;
                var confirmation=new CorrelationData();
                rabbit.convertAndSend(EXCHANGE,"","changed",confirmation);
                if (!confirmation.getFuture().get(1500,TimeUnit.MILLISECONDS).ack() || confirmation.getReturned()!=null) {
                    throw new IllegalStateException("Reminder broadcast not confirmed");
                }
                db.update("INSERT INTO reminder_broadcast_state(id,digest) VALUES (1,?) "
                        +"ON CONFLICT(id) DO UPDATE SET digest=excluded.digest",digest);
                meters.counter("personalos.reminders.broadcast.sent").increment();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                meters.counter("personalos.reminders.broadcast.retry").increment();
            }
        }
        @PreDestroy public void close() { executor.shutdownNow(); }
    }
}
