package com.personalos.maintenance;

import static org.assertj.core.api.Assertions.*;
import com.personalos.support.ContainerContextTest;
import com.personalos.maintenance.service.MaintenanceService;
import com.personalos.maintenance.controller.MaintenanceController;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.json.JsonCodec;
import com.personalos.export.DataExportService;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","app.workflows.enabled=false","app.maintenance.heartbeat-enabled=false","app.calendar.scan-enabled=false","app.knowledge.jobs.enabled=false","app.knowledge.rag.ingestion-review-enabled=false"})
@Testcontainers
class MaintenanceIntegrationTest extends ContainerContextTest {
 @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
 @TestConfiguration static class Config {
  @Bean org.springframework.ai.embedding.EmbeddingModel embeddingModel(){return new com.personalos.support.DeterministicEmbeddingModel(1024){@Override public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request){throw new AssertionError("维护校验不得调用模型");}};}
 }
 private final MaintenanceService service;private final MaintenanceController controller;private final JdbcTemplate jdbc;private final JsonCodec json;private final PlatformTransactionManager manager;private final DataExportService exporter;
 @Autowired MaintenanceIntegrationTest(MaintenanceService s,MaintenanceController c,JdbcTemplate j,JsonCodec codec,PlatformTransactionManager m,DataExportService e){service=s;controller=c;jdbc=j;json=codec;manager=m;exporter=e;}
 @BeforeEach void clean(){jdbc.execute("TRUNCATE maintenance_check,runtime_heartbeat,workflow,chat_session,task,calendar_event,memory,knowledge_document,knowledge_capture,knowledge_index_job CASCADE");}
 private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
 @SuppressWarnings("unchecked")
 @Test void sharedFixtureRulesAndPureValidation()throws Exception{
  jdbc.execute("INSERT INTO task(title) VALUES('业务保持不变')");String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t))::text FROM task t",String.class);
  var cases=json.fromJson(Files.readString(Path.of("..","specs","035-safe-automation","fixtures","maintenance.json")),List.class);
  for(Object value:cases){var fixture=(Map<String,Object>)value;String raw=json.toJson(fixture.get("payload"));var result=service.verify(bytes(raw));assertThat(result.get("status")).as((String)fixture.get("name")).isEqualTo(fixture.get("status"));if(fixture.get("issue")!=null)assertThat(json.toJson(result.get("issues"))).contains((String)fixture.get("issue"));assertThat(result.get("digest")).isEqualTo(JsonCodec.sha256Hex(raw));}
  var invalidJson=json.fromJson(Files.readString(Path.of("..","specs","035-safe-automation","fixtures","maintenance-invalid-json.json")),List.class);
  for(Object value:invalidJson){var fixture=(Map<String,Object>)value;String raw=(String)fixture.get("content");var result=service.verify(bytes(raw));assertThat(result.get("status")).as((String)fixture.get("name")).isEqualTo("INVALID");assertThat(result.get("digest")).isEqualTo(JsonCodec.sha256Hex(raw));assertThatThrownBy(()->service.drill(bytes(raw))).hasMessageContaining("外部报告");}
  for(var invalid:List.of(bytes("broken"),new byte[]{(byte)255},bytes("{\"x\":\"\\u0000\"}"),bytes("[]"),new byte[]{0}))assertThat(service.verify(invalid).get("status")).isEqualTo("INVALID");
  assertThatThrownBy(()->service.verify(new byte[MaintenanceService.MAX_BYTES+1])).hasMessageContaining("大小上限");
  assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(t))::text FROM task t",String.class)).isEqualTo(before);
  var output=new ByteArrayOutputStream();exporter.writeTo(output);assertThat(service.verify(output.toByteArray()).get("status")).isEqualTo("VALID");
  var request=new MockHttpServletRequest();request.setContent(bytes("bad"));assertThat(controller.verify(request).data().get("status")).isEqualTo("INVALID");assertThat(controller.snapshot().data()).containsKeys("heartbeats","failedIndexJobs","failedDeliveries","failedWorkflows","unfinishedRuns","checks");
 }
 @SuppressWarnings("unchecked")
 @Test void boundedHistoryDiagnosticsAndExternalReports()throws Exception{
  String raw="{\"formatVersion\":\"1\",\"sessions\":[],\"tasks\":"+json.toJson(Collections.nCopies(101,Map.of()))+",\"calendarEvents\":[],\"memories\":[],\"knowledgeDocuments\":[]}";
  var result=service.verify(bytes(raw));assertThat((List<Object>)result.get("issues")).hasSize(100);assertThat(result.get("truncated")).isEqualTo(true);
  String report="{\"tool\":\"scripts/dev/backup.py drill\",\"summary\":\"外部独立库演练结果\",\"outcome\":\"PASSED\",\"completedAt\":\"2026-11-06T00:00:00Z\"}";
  var request=new MockHttpServletRequest();request.setContent(bytes(report));var external=controller.drill(request).data();assertThat(external).containsEntry("status","UNVERIFIED").containsEntry("source","EXTERNAL").containsEntry("independentVerification",false);
  assertThatThrownBy(()->service.drill(bytes("{}"))).hasMessageContaining("外部报告");assertThatThrownBy(()->service.drill(new byte[]{(byte)255})).hasMessageContaining("UTF-8");assertThatThrownBy(()->service.drill(new byte[16385])).hasMessageContaining("大小上限");
  for(int i=0;i<205;i++)service.verify(bytes("marker-private-backup-content"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM maintenance_check",Integer.class)).isEqualTo(200);assertThat(jdbc.queryForObject("SELECT count(*) FROM maintenance_check WHERE result::text LIKE '%marker-private%'",Integer.class)).isZero();
 }
 @Test void genuineHeartbeatAndStaleState()throws Exception{
  service.heartbeat();assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_heartbeat",Integer.class)).isZero();
  var heartbeat=new MaintenanceService(jdbc,manager,json,new RuntimeRole("review"),true);heartbeat.heartbeat();String first=jdbc.queryForObject("SELECT last_seen_at::text FROM runtime_heartbeat",String.class);String started=jdbc.queryForObject("SELECT started_at::text FROM runtime_heartbeat",String.class);Thread.sleep(10);heartbeat.heartbeat();assertThat(jdbc.queryForObject("SELECT last_seen_at::text FROM runtime_heartbeat",String.class)).isNotEqualTo(first);assertThat(jdbc.queryForObject("SELECT started_at::text FROM runtime_heartbeat",String.class)).isEqualTo(started);
  assertThat(json.toJson(service.snapshot())).contains("ACTIVE","JAVA","review");jdbc.execute("UPDATE runtime_heartbeat SET last_seen_at=now()-interval '5 minutes'");assertThat(json.toJson(service.snapshot())).contains("STALE");jdbc.execute("INSERT INTO runtime_heartbeat(instance_id,backend,role,last_seen_at) VALUES(gen_random_uuid(),'GO','api',now()-interval '8 days')");heartbeat.heartbeat();assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_heartbeat",Integer.class)).isEqualTo(1);
 }
}
