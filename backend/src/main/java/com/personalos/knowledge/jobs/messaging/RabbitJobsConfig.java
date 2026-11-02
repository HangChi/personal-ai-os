package com.personalos.knowledge.jobs.messaging;

import com.personalos.knowledge.jobs.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="app.rabbitmq.enabled",havingValue="true")
public class RabbitJobsConfig {
    @Bean(destroyMethod="destroy") CachingConnectionFactory jobRabbitConnection(@Value("${app.rabbitmq.uri}") String uri) throws Exception {
        var connection=new CachingConnectionFactory(); connection.setUri(uri);
        connection.setConnectionTimeout(1000);
        connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connection.setPublisherReturns(true);
        return connection;
    }
    @Bean Queue knowledgeJobQueue(@Value("${app.rabbitmq.queue:personalos.knowledge.ready}") String name) {
        if (!name.matches("[a-zA-Z0-9_.-]{1,100}")) throw new IllegalArgumentException("任务队列名称不合法");
        return new Queue(name,true,false,false,java.util.Map.of("x-message-ttl",60000,"x-max-length",1000));
    }
    @Bean RabbitAdmin jobRabbitAdmin(CachingConnectionFactory factory) { return new RabbitAdmin(factory); }
    @Bean RabbitTemplate jobRabbitTemplate(CachingConnectionFactory factory) {
        var template=new RabbitTemplate(factory); template.setMandatory(true); return template;
    }
    @Bean KnowledgeJobNotifier knowledgeJobNotifier(RabbitTemplate template,Queue queue,MeterRegistry metrics) {
        return new RabbitJobNotifier(template,queue.getName(),metrics);
    }
    @Bean SimpleMessageListenerContainer knowledgeJobListener(CachingConnectionFactory factory,Queue queue,
            KnowledgeIndexJobs jobs,MeterRegistry metrics,com.personalos.common.config.RuntimeRole role) {
        var listener=new SimpleMessageListenerContainer(factory);
        listener.setQueueNames(queue.getName()); listener.setPrefetchCount(1);
        listener.setAutoStartup(role.allows("index"));
        listener.setMissingQueuesFatal(false); listener.setRecoveryInterval(5000);
        listener.setDefaultRequeueRejected(false);
        listener.setMessageListener((org.springframework.amqp.core.MessageListener) message -> {
            try { java.util.UUID.fromString(new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8)); }
            catch(IllegalArgumentException ex) { metrics.counter("personalos.jobs.notifications","result","invalid").increment(); return; }
            jobs.dispatch(); // ACK 的是通知，实际执行与重试仍取决于数据库领取与租约。
        });
        return listener;
    }
}
