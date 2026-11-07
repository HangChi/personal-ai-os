package com.personalos.workflow.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.workflow.dto.WorkflowChange;
import com.personalos.common.config.RuntimeRole;
import com.personalos.common.exception.*;
import com.personalos.common.json.JsonCodec;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;

class WorkflowBoundaryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private WorkflowService service(String role, boolean enabled) {
        var tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return spy(new WorkflowService(jdbc, tm, mock(JsonCodec.class), mock(ReadOnlyReports.class), new RuntimeRole(role), enabled));
    }
    @Test void optionalUpdateFieldsKeepExistingDefinitionAndNewDefinitionsUseDefaults() {
        var s = service("api", true);
        var id = UUID.randomUUID();
        var old = new WorkflowService.Definition(id, "saved", "WEEKLY_REVIEW", "UTC", "14:20", 7, true, 24, 100, 20000, "now");
        doReturn(old).when(s).get(any());
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=?)", Boolean.class, "UTC")).thenReturn(true);
        s.save(id, new WorkflowChange(null,null,null,null,null,null,null,null,null));
        verify(jdbc).update(startsWith("UPDATE workflow SET"), eq("saved"),eq("WEEKLY_REVIEW"),eq("UTC"),eq("14:20"),eq(7),eq(true),eq(24),eq(100),eq(20000),eq(id));
        when(jdbc.queryForObject("SELECT count(*) FROM workflow", Long.class)).thenReturn(0L);
        when(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_timezone_names WHERE name=?)", Boolean.class, "Asia/Shanghai")).thenReturn(true);
        assertThat(s.save(null, new WorkflowChange("new",null,null,null,null,null,null,null,null))).isSameAs(old);
        verify(jdbc).update(startsWith("INSERT INTO workflow"), any(UUID.class), eq("new"),eq("MORNING_BRIEF"),eq("Asia/Shanghai"),eq("09:00"),eq(1),eq(false),eq(3),eq(30),eq(10000));
    }
    @Test void retryRejectsMissingRunningAndExhaustedAttempts() {
        var s = service("api",true);var id=UUID.randomUUID();
        when(jdbc.queryForList("SELECT status,attempts FROM workflow_execution WHERE id=? FOR UPDATE",id)).thenReturn(List.of());
        assertThatThrownBy(() -> s.retry(id)).isInstanceOf(ResourceNotFoundException.class);
        for(var row:List.of(Map.of("status","RUNNING","attempts",1),Map.of("status","FAILED","attempts",3))) {
            doReturn(List.of(row)).when(jdbc).queryForList("SELECT status,attempts FROM workflow_execution WHERE id=? FOR UPDATE",id);
            assertThatThrownBy(() -> s.retry(id)).isInstanceOf(BusinessException.class);
        }
        verify(jdbc,never()).update(startsWith("UPDATE workflow_execution"),any(UUID.class));
    }
    @Test void schedulerHonorsRoleAndEnableFlag() {
        for(String role:List.of("api","review","combined"))for(boolean enabled:new boolean[]{true,false}) {
            var s=service(role,enabled);doNothing().when(s).dispatchDue();doReturn(false).when(s).runNext();
            s.tick();
            verify(s,enabled && !role.equals("api")?times(1):never()).dispatchDue();
            verify(s,enabled && !role.equals("api")?times(1):never()).runNext();
        }
    }

 @Test void rejectsEachWorkflowLimitAndUnknownZoneBeforeWriting(){
  var s=service("api",true);var old=new WorkflowService.Definition(UUID.randomUUID(),"saved","WEEKLY_REVIEW","UTC","14:20",7,true,24,100,20000,"now");
  doReturn(old).when(s).get(any());
  when(jdbc.queryForObject("SELECT count(*) FROM workflow",Long.class)).thenReturn(0L);
  for(var c:List.of(
   new WorkflowChange("",null,null,null,null,null,null,null,null),
   new WorkflowChange("a".repeat(101),null,null,null,null,null,null,null,null),
   new WorkflowChange("a","unknown",null,null,null,null,null,null,null),
   new WorkflowChange("a",null,"a".repeat(101),null,null,null,null,null,null),
   new WorkflowChange("a",null,"UTC","invalid",null,null,null,null,null),
   new WorkflowChange("a",null,"UTC","29:00",null,null,null,null,null),
   new WorkflowChange("a",null,"Invalid/Zone","09:00",null,null,null,null,null),
   new WorkflowChange("a",null,null,null,0,null,null,null,null),
   new WorkflowChange("a",null,null,null,8,null,null,null,null),
   new WorkflowChange("a",null,null,null,null,null,0,null,null),
   new WorkflowChange("a",null,null,null,null,null,25,null,null),
   new WorkflowChange("a",null,null,null,null,null,null,0,null),
   new WorkflowChange("a",null,null,null,null,null,null,101,null),
   new WorkflowChange("a",null,null,null,null,null,null,null,999),
   new WorkflowChange("a",null,null,null,null,null,null,null,20001))) {
    assertThatThrownBy(()->s.save(null,c)).isInstanceOf(BusinessException.class);
  }
  when(jdbc.queryForObject("SELECT count(*) FROM workflow",Long.class)).thenReturn(100L);
  assertThatThrownBy(()->s.save(null,new WorkflowChange("a",null,null,null,null,null,null,null,null))).hasMessageContaining("100");
 }
}
