package com.personalos.mcp.server;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.task.service.TaskService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.memory.service.MemoryService;
import com.personalos.task.entity.TaskStatus;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class PersonalMcpToolsBoundaryTest {
 @Test void readOnlyToolsNormalizeInputsAndDelegateToDomainServices(){
  var tasks=mock(TaskService.class);var knowledge=mock(KnowledgeRetrievalService.class);
  var calendar=mock(CalendarEventService.class);var memory=mock(MemoryService.class);
  var tools=new PersonalMcpTools(tasks,knowledge,calendar,memory);
  for(String status:Arrays.asList(null," "," todo ","DOING","done")){
   tools.listTasks(status,"keyword",null,null);
   verify(tasks).search(argThat(q->q.status()==(status==null||status.isBlank()?null:TaskStatus.valueOf(status.strip().toUpperCase(Locale.ROOT)))&&q.page()==0));
   clearInvocations(tasks);
  }
  tools.listTasks("TODO",null,2,10);verify(tasks).search(argThat(q->q.page()==2&&q.size()==10));
  assertThatThrownBy(()->tools.listTasks("invalid",null,null,null)).isInstanceOf(IllegalArgumentException.class);
  tools.listCalendar(null," ",null,null);verify(calendar).search(null,null,null,null);
  tools.listCalendar(" 2026-11-07T00:00:00Z ","2026-11-08T00:00:00Z",1,10);verify(calendar).search(Instant.parse("2026-11-07T00:00:00Z"),Instant.parse("2026-11-08T00:00:00Z"),1,10);
  assertThatThrownBy(()->tools.listCalendar("bad",null,null,null)).isInstanceOf(IllegalArgumentException.class);
  tools.searchKnowledge("evidence",3);verify(knowledge).search("evidence",3);tools.listMemories();verify(memory).list();
 }
}
