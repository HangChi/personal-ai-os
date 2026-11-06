package com.personalos.workflow.service;
import com.personalos.workflow.dto.WorkflowChange;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.config.RuntimeRole;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;

@Service
public class WorkflowService {
 private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final JsonCodec json;private final ReadOnlyReports reports;private final RuntimeRole role;private final boolean enabled;
 public WorkflowService(JdbcTemplate j,PlatformTransactionManager m,JsonCodec c,ReadOnlyReports r,RuntimeRole role,@Value("${app.workflows.enabled:true}")boolean enabled){jdbc=j;tx=new TransactionTemplate(m);json=c;reports=r;this.role=role;this.enabled=enabled;}
 public record Definition(UUID id,String name,String kind,String timezone,String localTime,int weekday,boolean enabled,int maxRunsPerDay,int maxItems,int maxOutputChars,String nextRunAt){}
 public record Attempt(int attempt,String status,String startedAt,String finishedAt,String error){}
 public record Execution(UUID id,UUID workflowId,String scheduledFor,String source,String status,int attempts,int modelCalls,String report,String error,String runId,String conversationId,List<Attempt> history){}
 private Definition map(java.sql.ResultSet r)throws java.sql.SQLException{return new Definition(r.getObject("id",UUID.class),r.getString("name"),r.getString("kind"),r.getString("timezone"),r.getString("local_time").substring(0,5),r.getInt("weekday"),r.getBoolean("enabled"),r.getInt("max_runs_per_day"),r.getInt("max_items"),r.getInt("max_output_chars"),r.getObject("next_run_at",OffsetDateTime.class).toInstant().toString());}
 private BusinessException invalid(String s){return new BusinessException(ErrorCode.VALIDATION_FAILED,s);}
 public Map<String,Object> list(){return Map.of("items",jdbc.query("SELECT * FROM workflow ORDER BY created_at DESC LIMIT 100",(r,n)->map(r)),"total",jdbc.queryForObject("SELECT count(*) FROM workflow",Long.class));}
 public Definition get(UUID id){return jdbc.query("SELECT * FROM workflow WHERE id=?",(r,n)->map(r),id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("计划不存在"));}
 public Definition save(UUID id,WorkflowChange change){return tx.execute(status->{
  jdbc.execute("SELECT pg_advisory_xact_lock(350046)");var old=id==null?null:get(id);
  if(id==null && jdbc.queryForObject("SELECT count(*) FROM workflow",Long.class)>=100)throw invalid("最多100个计划");
  String name=change.name()!=null?change.name().strip():old==null?"":old.name(),kind=change.kind()!=null?change.kind():old==null?"MORNING_BRIEF":old.kind(),zone=change.timezone()!=null?change.timezone():old==null?"Asia/Shanghai":old.timezone(),time=change.localTime()!=null?change.localTime():old==null?"09:00":old.localTime();
  int weekday=change.weekday()!=null?change.weekday():old==null?1:old.weekday(),runs=change.maxRunsPerDay()!=null?change.maxRunsPerDay():old==null?3:old.maxRunsPerDay(),items=change.maxItems()!=null?change.maxItems():old==null?30:old.maxItems(),chars=change.maxOutputChars()!=null?change.maxOutputChars():old==null?10000:old.maxOutputChars();boolean active=change.enabled()!=null?change.enabled():old!=null&&old.enabled();
  if(name.isEmpty()||name.length()>100||!Set.of("MORNING_BRIEF","WEEKLY_REVIEW","KNOWLEDGE_REVIEW").contains(kind)||zone.length()>100||!time.matches("[0-2][0-9]:[0-5][0-9]")||weekday<1||weekday>7||runs<1||runs>24||items<1||items>100||chars<1000||chars>20000)throw invalid("请检查计划名称、类型、时间和消费上限");
  try{LocalTime.parse(time);ZoneId.of(zone);}catch(DateTimeException ex){throw invalid("请选择有效时间和IANA时区");}
  if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=?)",Boolean.class,zone)))throw invalid("请选择有效IANA时区");
  UUID key=id==null?UUID.randomUUID():id;
  if(id==null)jdbc.update("INSERT INTO workflow(id,name,kind,timezone,local_time,weekday,enabled,max_runs_per_day,max_items,max_output_chars) VALUES(?,?,?,?,?::time,?,?,?,?,?)",key,name,kind,zone,time,weekday,active,runs,items,chars);
  else jdbc.update("UPDATE workflow SET name=?,kind=?,timezone=?,local_time=?::time,weekday=?,enabled=?,max_runs_per_day=?,max_items=?,max_output_chars=? WHERE id=?",name,kind,zone,time,weekday,active,runs,items,chars,key);
  return get(key);
 });}
 private Execution mapExecution(java.sql.ResultSet r)throws java.sql.SQLException{
  return new Execution(r.getObject("id",UUID.class),r.getObject("workflow_id",UUID.class),r.getObject("scheduled_for",OffsetDateTime.class).toInstant().toString(),r.getString("source"),r.getString("status"),r.getInt("attempts"),r.getInt("model_calls"),r.getString("report"),r.getString("error"),r.getString("run_id"),r.getString("conversation_id"),new ArrayList<>());
 }
 private List<Execution> withHistory(List<Execution> items){
  if(items.isEmpty())return items;
  Map<UUID,List<Attempt>> histories=new HashMap<>();
  for(var item:items)histories.put(item.id(),item.history());
  new NamedParameterJdbcTemplate(jdbc).query("SELECT execution_id,attempt,status,started_at::text,finished_at::text,error FROM workflow_attempt WHERE execution_id IN (:ids) ORDER BY execution_id,attempt",Map.of("ids",items.stream().map(Execution::id).toList()),(org.springframework.jdbc.core.RowCallbackHandler)r->histories.get(r.getObject(1,UUID.class)).add(new Attempt(r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6))));
  return items;
 }
 private static final String EXEC="SELECT e.*,r.conversation_id FROM workflow_execution e LEFT JOIN chat_run r ON r.id=e.run_id";
 public Execution execution(UUID id){return withHistory(jdbc.query(EXEC+" WHERE e.id=?",(r,n)->mapExecution(r),id)).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("执行记录不存在"));}
 public Execution run(UUID id){get(id);UUID execution=jdbc.queryForObject("SELECT enqueue_workflow(?,true)",UUID.class,id);return execution(execution);}
 public Execution retry(UUID id){return tx.execute(status->{var rows=jdbc.queryForList("SELECT status,attempts FROM workflow_execution WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw new ResourceNotFoundException("执行记录不存在");var row=rows.getFirst();if(!row.get("status").equals("FAILED")||((Number)row.get("attempts")).intValue()>=3)throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,"仅能重试未达到3次上限的失败期次");jdbc.update("UPDATE workflow_execution SET status='PENDING',source='MANUAL',error=NULL,available_at=now(),updated_at=now() WHERE id=?",id);return execution(id);});}
 public Map<String,Object> history(UUID id){get(id);return Map.of("items",withHistory(jdbc.query(EXEC+" WHERE workflow_id=? ORDER BY e.created_at DESC,e.id DESC LIMIT 50",(r,n)->mapExecution(r),id)),"total",jdbc.queryForObject("SELECT count(*) FROM workflow_execution WHERE workflow_id=?",Long.class,id));}
 public void dispatchDue(){jdbc.execute("SELECT dispatch_workflows()");}
 public boolean runNext(){
  record Claim(UUID id,UUID token,String definition,Instant scheduled){}
  var claims=jdbc.query("SELECT id,claim_token,definition::text,scheduled_for FROM claim_workflow()",(r,n)->new Claim(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,OffsetDateTime.class).toInstant()));
  if(claims.isEmpty())return false;var c=claims.getFirst();
  try{var definition=json.fromJson(c.definition(),ReadOnlyReports.Snapshot.class);String report=reports.generate(definition,c.scheduled());jdbc.queryForObject("SELECT finish_workflow(?,?,?,NULL)",Boolean.class,c.id(),c.token(),report);}
  catch(RuntimeException ex){jdbc.queryForObject("SELECT finish_workflow(?,?,NULL,?)",Boolean.class,c.id(),c.token(),"只读报告暂时失败，将在上限内重试");}
  return true;
 }
 @Scheduled(fixedDelayString="${app.workflows.poll-ms:1000}",initialDelay=2000) public void tick(){if(enabled&&role.allows("review")){dispatchDue();runNext();}}
}
