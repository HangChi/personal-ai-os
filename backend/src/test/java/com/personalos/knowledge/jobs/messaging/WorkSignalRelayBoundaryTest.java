package com.personalos.knowledge.jobs.messaging;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.web.DatabaseCoordination;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
class WorkSignalRelayBoundaryTest {
 @Test void confirmsRevisionBeforeSchedulingNextPublishAndRetriesUnconfirmedSignals(){
  for(String outcome:List.of("ack","nack","returned","throw","interrupted")){
   var db=mock(JdbcTemplate.class);var rabbit=mock(RabbitTemplate.class);var locks=mock(DatabaseCoordination.class);var lease=mock(DatabaseCoordination.Lease.class);var meters=new SimpleMeterRegistry();
   when(locks.acquire(anyString(),eq(1))).thenReturn(lease);var id=UUID.randomUUID();var rows=List.of(Map.<String,Object>of("topic","index","item_id",id,"revision",7));when(db.queryForList(anyString())).thenReturn(rows);
   doAnswer(i->{CorrelationData c=i.getArgument(3);if(outcome.equals("throw"))throw new IllegalStateException("broker unavailable");if(outcome.equals("interrupted")){Thread.currentThread().interrupt();return null;}if(outcome.equals("returned"))c.setReturned(new ReturnedMessage(new Message(new byte[0],new MessageProperties()),312,"NO_ROUTE","","q"));c.getFuture().complete(new CorrelationData.Confirm(outcome.equals("ack")||outcome.equals("returned"),null));return null;}).when(rabbit).convertAndSend(anyString(),anyString(),any(Object.class),any(CorrelationData.class));
   var relay=new WorkSignalRelay(db,rabbit,locks,meters,new RuntimeRole("api"),"fixture-index");try{relay.relay();if(outcome.equals("interrupted")){assertThat(Thread.interrupted()).isTrue();verify(db,never()).update(anyString(),any(Object[].class));}else{String result=outcome.equals("ack")?"confirmed":"retry";assertThat(meters.get("personalos.work.signals").tags("topic","index","result",result).counter().count()).isEqualTo(1);verify(db).update(contains(outcome.equals("ack")?"30 seconds":"5 seconds"),eq("index"),eq(id),eq(7));}verify(lease).close();}finally{Thread.interrupted();relay.close();}
  }
 }
 @Test void leaseContentionWorkerRoleBusyAndClosedExecutorDoNotPublish(){
  var db=mock(JdbcTemplate.class);var rabbit=mock(RabbitTemplate.class);var lock=mock(DatabaseCoordination.class);
  var relay=new WorkSignalRelay(db,rabbit,lock,new SimpleMeterRegistry(),new RuntimeRole("api"),"q");relay.relay();verifyNoInteractions(db,rabbit);
  ((AtomicBoolean)ReflectionTestUtils.getField(relay,"busy")).set(true);relay.dispatch();relay.close();((AtomicBoolean)ReflectionTestUtils.getField(relay,"busy")).set(false);relay.dispatch();assertThat(((AtomicBoolean)ReflectionTestUtils.getField(relay,"busy")).get()).isFalse();
  var worker=new WorkSignalRelay(db,rabbit,lock,new SimpleMeterRegistry(),new RuntimeRole("index"),"q");try{worker.dispatch();}finally{worker.close();}
 }
}
