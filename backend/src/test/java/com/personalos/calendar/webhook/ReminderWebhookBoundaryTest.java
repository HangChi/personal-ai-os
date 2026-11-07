package com.personalos.calendar.webhook;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ReminderWebhookBoundaryTest {
 static final String SECRET="synthetic-fixture-key-32-characters";
 @Test void rejectsUnsafeDestinationsAndWeakSecrets(){
  for(String url:List.of("http://example.com/hook","http://localhost/hook","https:/path","https://user:pass@example.com/hook","https://example.com/hook#fragment"))
   assertThatThrownBy(()->new ReminderWebhookWorker(mock(JdbcTemplate.class),new RuntimeRole("api"),new JsonCodec(new ObjectMapper()),new SimpleMeterRegistry(),url,SECRET,true)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new ReminderWebhookWorker(mock(JdbcTemplate.class),new RuntimeRole("api"),new JsonCodec(new ObjectMapper()),new SimpleMeterRegistry(),"https://example.com/hook","short",false)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void responsePolicyRetainsStableIdentityAndNeverFalselyAcknowledges() throws Exception {
  for(int status:new int[]{199,200,299,300,400,429,500})for(int attempts:new int[]{1,5}){
   var db=mock(JdbcTemplate.class);var meters=new SimpleMeterRegistry();
   var worker=new ReminderWebhookWorker(db,new RuntimeRole("delivery"),new JsonCodec(new ObjectMapper()),meters,"https://example.com/hook",SECRET,false);
   var client=mock(HttpClient.class);ReflectionTestUtils.setField(worker,"http",client);
   @SuppressWarnings("unchecked") HttpResponse<java.io.InputStream> response=mock(HttpResponse.class);
   when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
   UUID id=UUID.randomUUID();var row=Map.<String,Object>of("id",id,"attempts",attempts,"reminder_id",UUID.randomUUID(),"title","fixture","source_type","TASK","remind_at",Instant.EPOCH);
   try{
    ReflectionTestUtils.invokeMethod(worker,"send",row);
    String expected=status>=200&&status<300?"SENT":status!=429&&status<500||attempts==5?"FAILED":"PENDING";
    verify(db).update(contains("state=?"),eq(expected),eq(30<<attempts),eq(id),eq(attempts));
    verify(client).send(argThat(request->request.headers().firstValue("Idempotency-Key").orElse("").equals(id.toString())&&request.headers().firstValue("X-PersonalOS-Signature").orElse("").startsWith("sha256=")),any(HttpResponse.BodyHandler.class));
    assertThat(meters.get("personalos.reminders.webhook.deliveries").tag("result",expected.toLowerCase(Locale.ROOT)).counter().count()).isEqualTo(1);
   }finally{worker.close();meters.close();}
  }
 }
 @Test void interruptionLeavesClaimRecoverableAndDoesNotWriteReceipt() throws Exception {
  var db=mock(JdbcTemplate.class);var worker=new ReminderWebhookWorker(db,new RuntimeRole("api"),new JsonCodec(new ObjectMapper()),new SimpleMeterRegistry(),"http://127.0.0.1/hook",SECRET,true);
  var client=mock(HttpClient.class);ReflectionTestUtils.setField(worker,"http",client);when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
  var row=Map.<String,Object>of("id",UUID.randomUUID(),"attempts",5,"reminder_id",UUID.randomUUID(),"title","fixture","source_type","TASK","remind_at",Instant.EPOCH);
  try{ReflectionTestUtils.invokeMethod(worker,"send",row);assertThat(Thread.interrupted()).isTrue();verifyNoInteractions(db);worker.dispatch();verifyNoInteractions(db);}
  finally{Thread.interrupted();worker.close();}
  var delivery=new ReminderWebhookWorker(db,new RuntimeRole("delivery"),new JsonCodec(new ObjectMapper()),new SimpleMeterRegistry(),"https://example.com",SECRET,false);
  try{var busy=(AtomicBoolean)ReflectionTestUtils.getField(delivery,"busy");busy.set(true);delivery.dispatch();busy.set(false);delivery.close();delivery.dispatch();assertThat(busy.get()).isFalse();}finally{delivery.close();}
 }
}
