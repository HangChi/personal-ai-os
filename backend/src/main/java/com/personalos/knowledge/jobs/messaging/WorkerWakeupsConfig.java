package com.personalos.knowledge.jobs.messaging;

import com.personalos.common.config.RuntimeRole;
import com.personalos.knowledge.governance.IngestionReviewWorker;
import com.personalos.knowledge.search.SearchProjection;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")
public class WorkerWakeupsConfig {
    @Bean Declarables workerQueues() {
        return new Declarables(queue(WorkSignalRelay.REVIEW_QUEUE),queue(WorkSignalRelay.SEARCH_QUEUE));
    }
    private Queue queue(String name) { return new Queue(name,true,false,false,Map.of("x-message-ttl",60000,"x-max-length",1000)); }
    @Bean SimpleMessageListenerContainer reviewWakeups(CachingConnectionFactory factory,RuntimeRole role,
            ObjectProvider<IngestionReviewWorker> worker,MeterRegistry metrics) {
        return listener(factory,WorkSignalRelay.REVIEW_QUEUE,role.allows("review"),
                () -> worker.ifAvailable(IngestionReviewWorker::dispatch),metrics);
    }
    @Bean SimpleMessageListenerContainer searchWakeups(CachingConnectionFactory factory,RuntimeRole role,
            ObjectProvider<SearchProjection> worker,MeterRegistry metrics) {
        return listener(factory,WorkSignalRelay.SEARCH_QUEUE,role.allows("delivery"),
                () -> worker.ifAvailable(SearchProjection::dispatch),metrics);
    }
    private SimpleMessageListenerContainer listener(CachingConnectionFactory factory,String queue,boolean enabled,
                                                     Runnable wakeup,MeterRegistry metrics) {
        var listener=new SimpleMessageListenerContainer(factory);
        listener.setQueueNames(queue);listener.setAutoStartup(enabled);listener.setPrefetchCount(1);
        listener.setMissingQueuesFatal(false);listener.setRecoveryInterval(5000);listener.setDefaultRequeueRejected(false);
        listener.setMessageListener((MessageListener)message -> {
            try { UUID.fromString(new String(message.getBody(),StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ex) { metrics.counter("personalos.work.invalid").increment(); return; }
            wakeup.run();
        });
        return listener;
    }
}
