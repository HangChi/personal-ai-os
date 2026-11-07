package com.personalos.knowledge.search;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.knowledge.service.VectorStoreInspector;
import com.personalos.common.config.RuntimeRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class SearchProjectionBoundaryTest {
 @Test void roleAndSingleFlightControlOutboxDispatch() throws Exception {
  var db=mock(JdbcTemplate.class);var inspector=mock(VectorStoreInspector.class);var meters=new SimpleMeterRegistry();
  var api=new SearchProjection(db,inspector,mock(OpenSearchGateway.class),meters,new RuntimeRole("api"));
  api.sync();api.dispatch();verify(db).update(startsWith("INSERT INTO knowledge_search_outbox"));api.close();
  var started=new CountDownLatch(1);var unblock=new CountDownLatch(1);
  when(db.query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class))).thenAnswer(c->{started.countDown();try{unblock.await(2,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();}return List.of();});
  var delivery=new SearchProjection(db,inspector,mock(OpenSearchGateway.class),meters,new RuntimeRole("delivery"));
  try{delivery.dispatch();assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();delivery.dispatch();verify(db,times(1)).query(anyString(),any(org.springframework.jdbc.core.RowMapper.class),any(Object[].class));}
  finally{unblock.countDown();delivery.close();meters.close();}
  var closed=new SearchProjection(db,inspector,mock(OpenSearchGateway.class),new SimpleMeterRegistry(),new RuntimeRole("delivery"));closed.close();assertThatCode(closed::dispatch).doesNotThrowAnyException();
 }
}
