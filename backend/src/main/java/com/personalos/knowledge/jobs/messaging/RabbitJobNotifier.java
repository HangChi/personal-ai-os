package com.personalos.knowledge.jobs.messaging;

import com.personalos.knowledge.jobs.KnowledgeJobNotifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

public final class RabbitJobNotifier implements KnowledgeJobNotifier {
    private final RabbitTemplate template;
    private final String queue;
    private final MeterRegistry metrics;
    public RabbitJobNotifier(RabbitTemplate template,String queue,MeterRegistry metrics) {
        this.template=template; this.queue=queue; this.metrics=metrics;
    }
    @Override public void submitted(UUID id) {
        try {
            var correlation=new CorrelationData(UUID.randomUUID().toString());
            template.convertAndSend("",queue,id.toString(),correlation);
            var confirm=correlation.getFuture().get(1500,TimeUnit.MILLISECONDS);
            if (!confirm.ack() || correlation.getReturned()!=null) throw new IllegalStateException("任务通知未确认");
            metrics.counter("personalos.jobs.notifications","result","confirmed").increment();
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); failure();
        } catch(Exception ex) { failure(); }
    }
    private void failure() { metrics.counter("personalos.jobs.notifications","result","poll_fallback").increment(); }
}
