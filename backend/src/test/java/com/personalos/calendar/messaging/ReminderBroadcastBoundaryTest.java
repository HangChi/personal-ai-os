package com.personalos.calendar.messaging;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.calendar.service.ReminderService;
import com.personalos.calendar.service.ReminderStreamDelivery;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.DatabaseCoordination;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
class ReminderBroadcastBoundaryTest {
 @Test void snapshotDigestCommitsOnlyAfterBrokerAcknowledgesAndLeaseAlwaysCloses(){
  for(String outcome:List.of("ack","nack","returned","same","busy","interrupted")){
   var db=mock(JdbcTemplate.class);var rabbit=mock(RabbitTemplate.class);var locks=mock(DatabaseCoordination.class);var lease=mock(DatabaseCoordination.Lease.class);var reminders=mock(ReminderService.class);var meters=new SimpleMeterRegistry();
   when(locks.acquire(anyString(),eq(1))).thenReturn(outcome.equals("busy")?null:lease);when(reminders.list(null,true,500)).thenReturn(List.of());when(db.queryForList(anyString(),any(Object[].class))).thenReturn(outcome.equals("same")?List.of(Map.of("id",1)):List.of());
   doAnswer(i->{CorrelationData c=i.getArgument(3);if(outcome.equals("interrupted")){Thread.currentThread().interrupt();return null;}if(outcome.equals("returned"))c.setReturned(new ReturnedMessage(new Message(new byte[0],new MessageProperties()),312,"NO_ROUTE","x",""));c.getFuture().complete(new CorrelationData.Confirm(outcome.equals("ack")||outcome.equals("returned"),null));return null;}).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
   var publisher=new ReminderBroadcast.Publisher(new RuntimeRole("delivery"),locks,reminders,db,new JsonCodec(JsonMapper.builder().build()),rabbit,meters);try{ReflectionTestUtils.invokeMethod(publisher,"publish");if(outcome.equals("ack")){verify(db).update(startsWith("INSERT INTO reminder_broadcast_state"),anyString());assertThat(meters.get("personalos.reminders.broadcast.sent").counter().count()).isEqualTo(1);}else{verify(db,never()).update(anyString(),any(Object[].class));}if(outcome.equals("nack")||outcome.equals("returned"))assertThat(meters.get("personalos.reminders.broadcast.retry").counter().count()).isEqualTo(1);if(!outcome.equals("busy"))verify(lease).close();if(outcome.equals("interrupted"))assertThat(Thread.interrupted()).isTrue();}finally{Thread.interrupted();publisher.close();}
  }
 }
 @Test void topologyIsBoundedAndConsumerPublishesWithoutReadingMessageInstructions(){
  var cfg=new ReminderBroadcast();var exchange=cfg.reminderExchange();assertThat(exchange.isDurable()).isTrue();var sub=new ReminderBroadcast.Subscription();var topology=sub.reminderSubscription(exchange);assertThat(topology.getDeclarablesByType(org.springframework.amqp.core.Queue.class).getFirst().getArguments()).containsEntry("x-max-length",10);
  var streams=mock(ReminderStreamDelivery.class);var meters=new SimpleMeterRegistry();var listener=sub.reminderSubscriber(mock(CachingConnectionFactory.class),topology,streams,meters);((MessageListener)listener.getMessageListener()).onMessage(new Message("untrusted".getBytes(),new MessageProperties()));verify(streams).publish();assertThat(meters.get("personalos.reminders.broadcast.received").counter().count()).isEqualTo(1);
  var p=cfg.reminderPublisher(new RuntimeRole("api"),mock(DatabaseCoordination.class),mock(ReminderService.class),mock(JdbcTemplate.class),new JsonCodec(JsonMapper.builder().build()),mock(RabbitTemplate.class),meters);p.dispatch();p.close();
  var delivery=cfg.reminderPublisher(new RuntimeRole("delivery"),mock(DatabaseCoordination.class),mock(ReminderService.class),mock(JdbcTemplate.class),new JsonCodec(JsonMapper.builder().build()),mock(RabbitTemplate.class),meters);((AtomicBoolean)ReflectionTestUtils.getField(delivery,"busy")).set(true);delivery.dispatch();delivery.close();((AtomicBoolean)ReflectionTestUtils.getField(delivery,"busy")).set(false);delivery.dispatch();assertThat(((AtomicBoolean)ReflectionTestUtils.getField(delivery,"busy")).get()).isFalse();
 }
}
