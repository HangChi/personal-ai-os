package com.personalos.chat.service;
import static org.assertj.core.api.Assertions.*;
import com.personalos.chat.dto.ChatRunDto;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
class RunEventDeliveryBoundaryTest {
 @Test void replaysFullBatchesUntilTerminalAndReleasesSignal()throws Exception{
  var hub=new RunEventHub();var delivery=new RunEventDelivery(hub);var calls=new AtomicInteger();var done=new CountDownLatch(1);
  var events=new ArrayList<ChatRunDto.Event>();for(int i=1;i<=200;i++)events.add(new ChatRunDto.Event(i,"delta","{}"));
  delivery.open("run",-1,cursor->{int call=calls.incrementAndGet();if(call==1){assertThat(cursor).isZero();return new ChatRunDto("run","c","COMPLETED",events,null,List.of(),null);}assertThat(cursor).isEqualTo(200);done.countDown();return new ChatRunDto("run","c","COMPLETED",List.of(),null,List.of(),null);});
  assertThat(done.await(2,TimeUnit.SECONDS)).isTrue();delivery.close();
 }
 @Test void runningReaderWakesAndInterruptsWithoutClosingAgent()throws Exception{
  var hub=new RunEventHub();var delivery=new RunEventDelivery(hub);var read=new CountDownLatch(1);var next=new CountDownLatch(1);var calls=new AtomicInteger();
  delivery.open("run",0,cursor->{if(calls.incrementAndGet()>1)next.countDown();read.countDown();return new ChatRunDto("run","c","RUNNING",List.of(),null,List.of(),null);});
  assertThat(read.await(2,TimeUnit.SECONDS)).isTrue();hub.signal("run");assertThat(next.await(3,TimeUnit.SECONDS)).isTrue();delivery.close();
  for(RuntimeException failure:List.of(new IllegalStateException("closed"),new IllegalArgumentException("bad read"))){
   var d=new RunEventDelivery(hub);var failed=new CountDownLatch(1);
   d.open("failed",0,cursor->{failed.countDown();throw failure;});
   assertThat(failed.await(2,TimeUnit.SECONDS)).isTrue();d.close();
  }
 }
}
