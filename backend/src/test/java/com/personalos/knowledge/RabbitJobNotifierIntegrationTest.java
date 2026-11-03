package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.personalos.knowledge.jobs.messaging.RabbitJobNotifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RabbitJobNotifierIntegrationTest {
    @Container static final GenericContainer<?> BROKER=new GenericContainer<>(DockerImageName.parse("rabbitmq:4.4.0-management"))
            .withEnv("RABBITMQ_DEFAULT_USER","test").withEnv("RABBITMQ_DEFAULT_PASS","test-password").withExposedPorts(5672);
    @Test void confirmsDurableIdNotificationsAndMissingRouteFallsBackWithoutLosingDatabaseWork() {
        var factory=new CachingConnectionFactory(BROKER.getHost(),BROKER.getMappedPort(5672));
        factory.setUsername("test"); factory.setPassword("test-password"); factory.setConnectionTimeout(1000);
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED); factory.setPublisherReturns(true);
        try {
            var admin=new RabbitAdmin(factory);
            String queue="test."+UUID.randomUUID(); admin.declareQueue(new Queue(queue,true));
            var template=new RabbitTemplate(factory); template.setMandatory(true);
            var metrics=new SimpleMeterRegistry();
            UUID id=UUID.randomUUID();
            new RabbitJobNotifier(template,queue,metrics).submitted(id);
            assertThat(template.receiveAndConvert(queue,3000)).isEqualTo(id.toString());
            assertThat(metrics.get("personalos.jobs.notifications").tag("result","confirmed").counter().count()).isEqualTo(1);
            new RabbitJobNotifier(template,"missing.queue",metrics).submitted(id);
            assertThat(metrics.get("personalos.jobs.notifications").tag("result","poll_fallback").counter().count()).isEqualTo(1);
            admin.deleteQueue(queue);
        } finally { factory.destroy(); }
    }
    @Test void fanoutDeliversToTwoIndependentSubscribers() {
        var factory=new CachingConnectionFactory(BROKER.getHost(),BROKER.getMappedPort(5672));
        factory.setUsername("test"); factory.setPassword("test-password");
        try {
            var admin=new RabbitAdmin(factory);
            var exchange=new FanoutExchange("test.fanout."+UUID.randomUUID());
            var first=new AnonymousQueue();
            var second=new AnonymousQueue();
            admin.declareExchange(exchange);
            admin.declareQueue(first); admin.declareQueue(second);
            admin.declareBinding(BindingBuilder.bind(first).to(exchange));
            admin.declareBinding(BindingBuilder.bind(second).to(exchange));
            var template=new RabbitTemplate(factory);
            template.convertAndSend(exchange.getName(),"","changed");
            assertThat(template.receiveAndConvert(first.getName(),3000)).isEqualTo("changed");
            assertThat(template.receiveAndConvert(second.getName(),3000)).isEqualTo("changed");
        } finally { factory.destroy(); }
    }
}
