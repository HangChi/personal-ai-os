package com.personalos.workflow;

import static org.assertj.core.api.Assertions.*;
import com.personalos.support.ContainerContextTest;
import com.personalos.workflow.service.*;
import com.personalos.workflow.dto.WorkflowChange;
import com.personalos.workflow.controller.WorkflowController;
import com.personalos.chat.service.*;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.common.json.JsonCodec;
import com.personalos.export.*;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"spring.ai.model.chat=none","spring.ai.model.embedding=none","app.workflows.enabled=false","app.calendar.scan-enabled=false","app.knowledge.jobs.enabled=false","app.knowledge.rag.ingestion-review-enabled=false"})
@Testcontainers
class WorkflowIntegrationTest extends ContainerContextTest {
 @org.springframework.boot.test.context.TestConfiguration static class Config {
  @org.springframework.context.annotation.Bean org.springframework.ai.embedding.EmbeddingModel embeddingModel(){return new com.personalos.support.DeterministicEmbeddingModel(1024){@Override public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request){throw new AssertionError("只读工作流不得调用模型");}};}
 }
 @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
 private final WorkflowService service;private final JdbcTemplate jdbc;private final ReadOnlyReports reports;private final ChatSessionService sessions;private final ChatRunService runs;private final ChatMessageService messages;private final DataExportService exporter;private final DataImportService importer;private final JsonCodec json;
 @Autowired WorkflowIntegrationTest(WorkflowService s,JdbcTemplate j,ReadOnlyReports r,ChatSessionService cs,ChatRunService cr,ChatMessageService cm,DataExportService de,DataImportService di,JsonCodec jc){service=s;jdbc=j;reports=r;sessions=cs;runs=cr;messages=cm;exporter=de;importer=di;json=jc;}
 @BeforeEach void clean(){jdbc.execute("TRUNCATE workflow,chat_session,task,calendar_event,knowledge_document,knowledge_capture CASCADE");}
 private WorkflowChange change(String name,String kind,Boolean enabled,Integer quota){return new WorkflowChange(name,kind,null,null,null,enabled,quota,null,null);}
 @Test void nonSchedulingEditsPreserveDuePeriodAndEnableRecalculates(){
  var d=service.save(null,change("到期计划",null,true,null));
  jdbc.update("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=?",d.id());
  String due=service.get(d.id()).nextRunAt();
  var edits=List.of(change("仅改名",null,null,null),new WorkflowChange(null,null,null,null,null,null,12,10,2000),new WorkflowChange("重复保存",d.kind(),d.timezone(),d.localTime(),d.weekday(),true,12,10,2000));
  for(var edit:edits)assertThat(service.save(d.id(),edit).nextRunAt()).isEqualTo(due);
  service.dispatchDue();service.dispatchDue();
  assertThat(jdbc.queryForObject("SELECT scheduled_for FROM workflow_execution WHERE workflow_id=?",OffsetDateTime.class,d.id()).toInstant()).isEqualTo(Instant.parse(due));
  assertThat(service.history(d.id()).get("total")).isEqualTo(1L);
  assertThat(Instant.parse(service.get(d.id()).nextRunAt())).isAfter(Instant.now());
  jdbc.update("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=?",d.id());due=service.get(d.id()).nextRunAt();
  assertThat(service.save(d.id(),change(null,null,false,null)).nextRunAt()).isEqualTo(due);
  assertThat(Instant.parse(service.save(d.id(),change(null,null,true,null)).nextRunAt())).isAfter(Instant.now());
  for(var edit:List.of(new WorkflowChange(null,null,null,"10:30",null,null,null,null,null),new WorkflowChange(null,null,"UTC",null,null,null,null,null,null),change(null,"WEEKLY_REVIEW",null,null),new WorkflowChange(null,null,null,null,2,null,null,null,null))){
   jdbc.update("UPDATE workflow SET next_run_at=now()-interval '1 hour' WHERE id=?",d.id());
   assertThat(Instant.parse(service.save(d.id(),edit).nextRunAt())).isAfter(Instant.now());
  }
 }
 @Test void reportsReservePrioritySectionsAndReuseUnusedSlots(){
  jdbc.execute("INSERT INTO knowledge_document(title,filename,content_type,size_bytes,char_count) SELECT '普通资料'||i,'fixture.txt','text/plain',1,1 FROM generate_series(1,30)i");
  jdbc.execute("INSERT INTO knowledge_capture(canonical_url,last_error,last_checked_at) VALUES('https://example.com/failed','失败采集样例',now()-interval '10 days')");
  var knowledge=new ReadOnlyReports.Snapshot("巡检","KNOWLEDGE_REVIEW","UTC",30,20000);
  String report=reports.generate(knowledge,Instant.now());
  assertThat(report.lines().filter(v->v.startsWith("• ")).count()).isEqualTo(30);
  assertThat(report.lines().filter(v->v.startsWith("• 资料：")).count()).isEqualTo(29);
  assertThat(report).contains("失败采集样例","达到数据项上限");
  jdbc.execute("INSERT INTO knowledge_capture(canonical_url,pending_job_id,last_checked_at) SELECT 'https://example.com/pending/'||i,gen_random_uuid(),now() FROM generate_series(1,35)i");
  report=reports.generate(knowledge,Instant.now());
  assertThat(report.lines().filter(v->v.startsWith("• 采集：")).count()).isEqualTo(15);
  assertThat(report.lines().filter(v->v.startsWith("• 资料：")).count()).isEqualTo(15);
  assertThat(report).contains("失败采集样例");
  report=reports.generate(new ReadOnlyReports.Snapshot("巡检","KNOWLEDGE_REVIEW","UTC",1,20000),Instant.now());
  assertThat(report).contains("失败采集样例").doesNotContain("• 资料：","正在索引");
  Instant day=LocalDate.of(2026,11,6).atStartOfDay(ZoneOffset.UTC).toInstant();
  jdbc.execute("INSERT INTO task(title,due_at) SELECT '晨报任务'||i,'2026-11-06T01:00Z'::timestamptz FROM generate_series(1,30)i");
  jdbc.execute("INSERT INTO task(title,status,completed_at) SELECT '周报任务'||i,'DONE','2026-11-05T01:00Z'::timestamptz FROM generate_series(1,30)i");
  jdbc.execute("INSERT INTO calendar_event(title,starts_at,ends_at) SELECT '晨报日程'||i,'2026-11-06T02:00Z'::timestamptz,'2026-11-06T03:00Z'::timestamptz FROM generate_series(1,4)i UNION ALL SELECT '周报日程'||i,'2026-11-05T02:00Z'::timestamptz,'2026-11-05T03:00Z'::timestamptz FROM generate_series(1,4)i");
  for(String kind:List.of("MORNING_BRIEF","WEEKLY_REVIEW")){
   report=reports.generate(new ReadOnlyReports.Snapshot("报告",kind,"UTC",4,20000),day);
   assertThat(report.lines().filter(v->v.startsWith("• 日程：")).count()).isEqualTo(2);
   assertThat(report.lines().filter(v->v.startsWith("• 待办：")||v.startsWith("• 已完成：")).count()).isEqualTo(2);
   report=reports.generate(new ReadOnlyReports.Snapshot("报告",kind,"UTC",1,20000),day);
   assertThat(report.lines().filter(v->v.startsWith("• ")).toList()).singleElement().asString().startsWith("• 日程：");
  }
  jdbc.execute("TRUNCATE calendar_event CASCADE");
  report=reports.generate(new ReadOnlyReports.Snapshot("报告","MORNING_BRIEF","UTC",4,20000),day);
  assertThat(report.lines().filter(v->v.startsWith("• 待办：")).count()).isEqualTo(4);
 }
 @SuppressWarnings("unchecked")
 @Test void fiftyExecutionHistoriesUseFourQueriesAndKeepAttemptsOrdered(){
  var d=service.save(null,change("历史查询",null,null,null));
  var count=new java.util.concurrent.atomic.AtomicInteger();
  var source=java.util.Objects.requireNonNull(jdbc.getDataSource());
  var countedJdbc=new JdbcTemplate(source){
   @Override public <T> T query(org.springframework.jdbc.core.PreparedStatementCreator creator,org.springframework.jdbc.core.PreparedStatementSetter setter,org.springframework.jdbc.core.ResultSetExtractor<T> extractor){count.incrementAndGet();return super.query(creator,setter,extractor);}
  };
  var counted=new WorkflowService(countedJdbc,new org.springframework.jdbc.datasource.DataSourceTransactionManager(source),json,reports,new com.personalos.common.config.RuntimeRole("review"),false);
  assertThat((List<?>)counted.history(d.id()).get("items")).isEmpty();assertThat(count.get()).isEqualTo(3);count.set(0);
  jdbc.update("INSERT INTO workflow_execution(workflow_id,scheduled_for,source,definition,status,attempts,created_at) SELECT id,'2026-01-01'::timestamptz+i*interval '1 minute','MANUAL',to_jsonb(w),'FAILED',i%4,'2026-01-01'::timestamptz+i*interval '1 minute' FROM workflow w CROSS JOIN generate_series(1,51)i WHERE id=?",d.id());
  jdbc.execute("INSERT INTO workflow_attempt(execution_id,attempt,status,error) SELECT e.id,a,'FAILED',e.id::text||'/'||a FROM workflow_execution e CROSS JOIN LATERAL generate_series(1,e.attempts)a ORDER BY a DESC");
  var page=counted.history(d.id());assertThat(count.get()).isEqualTo(4);assertThat(page.get("total")).isEqualTo(51L);
  var items=(List<WorkflowService.Execution>)page.get("items");assertThat(items).hasSize(50);
  for(var item:items){assertThat(item.history()).hasSize(item.attempts());for(int i=0;i<item.history().size();i++){assertThat(item.history().get(i).attempt()).isEqualTo(i+1);assertThat(item.history().get(i).error()).isEqualTo(item.id()+"/"+(i+1));}}
  count.set(0);var single=counted.execution(items.getFirst().id());assertThat(count.get()).isEqualTo(2);assertThat(single.history()).isEqualTo(items.getFirst().history());
 }
 @Test void manualDedupReportRunReadOnlyAndBusinessBackup()throws Exception{
  var controller=new WorkflowController(service);var d=controller.create(change("每日晨报",null,null,24)).data();assertThat(d.enabled()).isFalse();service.dispatchDue();assertThat(jdbc.queryForObject("SELECT count(*) FROM workflow_execution",Integer.class)).isZero();
  UUID id;try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->service.run(d.id()));var b=pool.submit(()->service.run(d.id()));id=a.get().id();assertThat(b.get().id()).isEqualTo(id);}
  jdbc.execute("INSERT INTO task(title,due_at) VALUES('逾期待办',now()-interval '1 day')");assertThat(service.runNext()).isTrue();var result=service.execution(id);
  assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.modelCalls()).isZero();assertThat(result.report()).contains("逾期待办","零模型调用");assertThat(result.history()).hasSize(1);assertThat(result.runId()).isNotNull();
  assertThat(jdbc.queryForObject("SELECT origin FROM chat_run WHERE id=?",String.class,result.runId())).isEqualTo("READONLY_WORKFLOW");assertThat(sessions.requireSession(result.conversationId()).isReadOnlyReport()).isTrue();
  assertThatThrownBy(()->runs.start(result.conversationId(),"unsafe",new StreamChatRequest("write",false,null,null,null))).hasMessageContaining("只读");
  assertThatThrownBy(()->runs.resume(result.conversationId(),result.runId())).hasMessageContaining("只读");assertThatThrownBy(()->messages.rewind(result.conversationId(),1)).hasMessageContaining("只读");
  assertThatThrownBy(()->jdbc.update("INSERT INTO chat_message(conversation_id,message_type,content,sequence_no) VALUES(?,'USER','unsafe',2)",result.conversationId())).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->jdbc.update("UPDATE chat_session SET read_only_report=false WHERE conversation_id=?",result.conversationId())).isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM task",Integer.class)).isEqualTo(1);
  var output=new ByteArrayOutputStream();exporter.writeTo(output);String payload=output.toString(java.nio.charset.StandardCharsets.UTF_8);assertThat(payload).contains("\"readOnlyReport\":true");
  jdbc.execute("TRUNCATE workflow,chat_session,task,calendar_event,knowledge_document,knowledge_capture,confirmation,operation_record CASCADE");importer.importAll(payload);
  assertThat(sessions.requireSession(result.conversationId()).isReadOnlyReport()).isTrue();assertThatThrownBy(()->sessions.assertWritable(result.conversationId())).hasMessageContaining("只读");
  assertThat(controller.list().data().get("total")).isEqualTo(0L);
 }
 @Test void allKindsLimitsConfigurationAndErrors(){
  assertThat(json.toJson(new com.personalos.chat.dto.ChatSessionDto("normal","普通会话",null,null,0))).doesNotContain("readOnlyReport");
  for(String kind:List.of("MORNING_BRIEF","WEEKLY_REVIEW","KNOWLEDGE_REVIEW")){
   var d=service.save(null,change(kind,kind,null,null));var execution=service.run(d.id());assertThat(service.runNext()).isTrue();var result=service.execution(execution.id());assertThat(result.status()).isEqualTo("SUCCEEDED");assertThat(result.report()).contains("零模型调用");assertThat(service.history(d.id()).get("total")).isEqualTo(1L);
   assertThat(service.save(d.id(),new WorkflowChange("修改",kind,"UTC","10:30",2,true,2,1,1000)).name()).isEqualTo("修改");
  }
  jdbc.execute("INSERT INTO task(title,due_at) SELECT repeat('长',180),now() FROM generate_series(1,10)");var report=reports.generate(new ReadOnlyReports.Snapshot("输出限制","MORNING_BRIEF","UTC",8,1000),Instant.now());assertThat(report.codePointCount(0,report.length())).isEqualTo(1000);assertThat(report).contains("达到输出上限");
  assertThat(reports.generate(new ReadOnlyReports.Snapshot("项数限制","MORNING_BRIEF","UTC",1,20000),Instant.now())).contains("达到数据项上限");
  for(var bad:List.of(new WorkflowChange("",null,null,null,null,null,null,null,null),new WorkflowChange("x","BAD",null,null,null,null,null,null,null),new WorkflowChange("x",null,"BAD","09:00",null,null,null,null,null),new WorkflowChange("x",null,null,"25:00",null,null,null,null,null),new WorkflowChange("x",null,null,null,null,null,0,null,null),new WorkflowChange("x",null,null,null,null,null,null,101,null))){assertThatThrownBy(()->service.save(null,bad)).isInstanceOf(RuntimeException.class);}
  assertThatThrownBy(()->service.retry(UUID.randomUUID())).hasMessageContaining("不存在");assertThatThrownBy(()->service.get(UUID.randomUUID())).hasMessageContaining("不存在");assertThatThrownBy(()->service.execution(UUID.randomUUID())).hasMessageContaining("不存在");
 }
 @Test void schedulingLeaseFencingDailyQuotaAndAttemptCap(){
  assertThat(jdbc.queryForObject("SELECT workflow_next_at('America/New_York','02:30',1,'MORNING_BRIEF','2026-03-08T00:00Z')",OffsetDateTime.class).toInstant()).isEqualTo(Instant.parse("2026-03-08T07:30:00Z"));
  assertThat(jdbc.queryForObject("SELECT workflow_next_at('America/New_York','01:30',1,'MORNING_BRIEF','2026-11-01T00:00Z')",OffsetDateTime.class).toInstant()).isEqualTo(Instant.parse("2026-11-01T06:30:00Z"));
  var d=service.save(null,change("失败",null,null,24));var run=service.run(d.id());UUID token=jdbc.queryForObject("SELECT claim_token FROM claim_workflow()",UUID.class);
  assertThat(jdbc.queryForObject("SELECT finish_workflow(?,?,NULL,'fail')",Boolean.class,run.id(),token)).isTrue();jdbc.update("UPDATE workflow_execution SET available_at=now()-interval '1 second' WHERE id=?",run.id());var token2=jdbc.queryForObject("SELECT claim_token FROM claim_workflow()",UUID.class);
  assertThat(jdbc.queryForObject("SELECT finish_workflow(?,?,'stale',NULL)",Boolean.class,run.id(),token)).isFalse();assertThat(token2).isNotEqualTo(token);
  jdbc.update("UPDATE workflow_execution SET lease_until=now()-interval '1 second' WHERE id=?",run.id());jdbc.queryForObject("SELECT claim_token FROM claim_workflow()",UUID.class);jdbc.update("UPDATE workflow_execution SET lease_until=now()-interval '1 second' WHERE id=?",run.id());service.runNext();assertThat(service.execution(run.id()).attempts()).isEqualTo(3);assertThat(service.execution(run.id()).status()).isEqualTo("FAILED");assertThatThrownBy(()->service.retry(run.id())).hasMessageContaining("上限");
  var quota=service.save(null,change("配额",null,null,1));service.run(quota.id());service.runNext();var second=service.run(quota.id());service.runNext();assertThat(service.execution(second.id()).attempts()).isZero();assertThat(service.execution(second.id()).status()).isEqualTo("FAILED");service.retry(second.id());service.runNext();assertThat(service.execution(second.id()).attempts()).isZero();
  var auto=service.save(null,change("定时",null,true,3));jdbc.update("UPDATE workflow SET next_run_at=now()-interval '10 days' WHERE id=?",auto.id());service.dispatchDue();service.dispatchDue();assertThat(service.history(auto.id()).get("total")).isEqualTo(1L);assertThat(Instant.parse(service.get(auto.id()).nextRunAt())).isAfter(Instant.now());service.save(auto.id(),change(null,null,false,null));service.runNext();assertThat(jdbc.queryForObject("SELECT status FROM workflow_execution WHERE workflow_id=?",String.class,auto.id())).isEqualTo("FAILED");
 }
}
