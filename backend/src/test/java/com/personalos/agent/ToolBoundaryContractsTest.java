package com.personalos.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.agent.tool.TaskTools;
import com.personalos.calendar.tool.CalendarTools;
import com.personalos.calendar.confirmation.CalendarConfirmationService;
import com.personalos.calendar.service.*;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.tool.*;
import com.personalos.common.api.PageDto;
import com.personalos.knowledge.dto.*;
import com.personalos.knowledge.rag.RagResult;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import com.personalos.knowledge.tool.KnowledgeTools;
import com.personalos.task.confirmation.TaskConfirmationService;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.service.TaskService;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;

class ToolBoundaryContractsTest {
 // The tool executor is tested separately. Execute each tool's business action here.
 static ToolExecutor executor() {
  var executor=mock(ToolExecutor.class);
  org.mockito.stubbing.Answer<String> action=i->{
   @SuppressWarnings("unchecked") var supplier=(Supplier<ToolExecutionResult>)i.getArgument(3);
   var result=supplier.get();return result.status()+":"+result.message();
  };
  doAnswer(action).when(executor).read(anyString(),anyMap(),nullable(ToolContext.class),any());
  doAnswer(action).when(executor).write(anyString(),anyMap(),nullable(ToolContext.class),any());
  return executor;
 }
 @Test void taskToolsValidateOptionalChangesAndBulkDeletionBeforeConfirmation(){
  var service=mock(TaskService.class);var confirmations=mock(TaskConfirmationService.class);
  var tools=new TaskTools(executor(),service,confirmations);String id=UUID.randomUUID().toString();
  assertThat(tools.updateTaskDetails(id,null,null,null,null)).startsWith("REJECTED:");
  assertThat(tools.setTaskCompleted(id,null,null)).startsWith("REJECTED:");
  for(List<String> ids:Arrays.<List<String>>asList(null,List.of(),Collections.nCopies(51,id),List.of(id)))
   assertThat(tools.deleteTask(ids,null)).startsWith("REJECTED:");
  var task=mock(TaskDto.class);when(task.title()).thenReturn("test");when(task.id()).thenReturn(UUID.fromString(id));
  when(service.update(any(),any())).thenReturn(task);when(service.setCompleted(any(),anyBoolean())).thenReturn(task);
  assertThat(tools.updateTaskDetails(id,"new",null,null,null)).startsWith("OK:");
  assertThat(tools.updateTaskDetails(id,null,"description",null,null)).startsWith("OK:");
  assertThat(tools.updateTaskDetails(id,null,null,List.of(),null)).startsWith("OK:");
  assertThat(tools.setTaskCompleted(id,true,null)).contains("已完成");
  assertThat(tools.setTaskCompleted(id,false,null)).contains("已恢复");
  when(service.search(any())).thenReturn(PageDto.of(List.of(),0,20));
  assertThat(tools.findTasks(null,null,null,null,null)).contains("没有");
  when(service.search(any())).thenReturn(PageDto.of(List.of(task),0,20));
  assertThat(tools.findTasks("test","TODO",null,100,null)).contains("1 个");
  verifyNoInteractions(confirmations);
 }
 @Test void calendarToolsRejectMissingTimesAndBulkRequestsWithoutConfirmation(){
  var events=mock(CalendarEventService.class);var reminders=mock(ReminderService.class);
  var confirmations=mock(CalendarConfirmationService.class);var tools=new CalendarTools(events,reminders,confirmations,executor());
  for(String[] time:new String[][]{{null,null},{"2026-11-07T00:00:00Z",null},{null,"2026-11-07T01:00:00Z"}})
   assertThat(tools.createCalendarEvent("title",time[0],time[1],null,null,null,null,null,null,null)).startsWith("REJECTED:");
  String id=UUID.randomUUID().toString();
  for(List<String> ids:Arrays.<List<String>>asList(null,List.of(),Collections.nCopies(51,id),List.of(id)))
   assertThat(tools.deleteCalendarEvent(ids,null)).startsWith("REJECTED:");
  when(events.search(any(),any(),any(),any())).thenReturn(PageDto.of(List.of(),0,10));
  assertThat(tools.findCalendarEvents(null,null,null,null)).contains("没有");
  when(events.search(any(),any(),any(),any())).thenReturn(PageDto.of(List.of(mock(com.personalos.calendar.dto.CalendarOccurrenceDto.class)),0,10));
  assertThat(tools.findCalendarEvents(null,null,10,null)).contains("1 项");
  assertThat(tools.findDueReminders(null,null)).contains("没有");
  when(reminders.list(null,true,10)).thenReturn(List.of(mock(com.personalos.calendar.dto.ReminderDto.class)));
  assertThat(tools.findDueReminders(10,null)).contains("1 条");verifyNoInteractions(confirmations);
 }
 @Test void knowledgeToolsPreserveGuardedStatusAndGlobalCitationRegistration(){
  @SuppressWarnings("unchecked") ObjectProvider<KnowledgeSearchPort> provider=mock(ObjectProvider.class);
  var search=mock(KnowledgeSearchPort.class);when(provider.getObject()).thenReturn(search);
  var tools=new KnowledgeTools(provider,executor());
  var blocked=new ToolContext(Map.of(ToolContextKeys.KNOWLEDGE_BLOCKED,true));
  assertThat(tools.searchKnowledge("query",4,blocked)).startsWith("REJECTED:");
  assertThat(tools.searchKnowledge(null,4,null)).startsWith("REJECTED:");
  assertThat(tools.searchKnowledge(" ",4,new ToolContext(Map.of()))).startsWith("REJECTED:");verifyNoInteractions(search);
  var hit=new KnowledgeHitDto(UUID.randomUUID(),"source","a.md",0,"evidence",.8);
  for(boolean guarded:List.of(false,true))for(boolean empty:List.of(false,true))for(boolean registered:List.of(false,true)){
   var hits=empty?List.<KnowledgeHitDto>of():List.of(hit);
   var result=new RagResult(hits,List.of("query"),List.of(),List.of(),guarded?"UNAVAILABLE":"READY",List.of(),"needs verification");
   when(search.retrieve("query",4)).thenReturn(result);
   KnowledgeTools.RegisterSources register=sources->{assertThat(sources).containsExactly(hit);return List.of(CitationDto.fromHit(9,hit));};
   ToolContext context=registered?new ToolContext(Map.of(ToolContextKeys.KNOWLEDGE_SOURCES,register)):null;
   String output=tools.searchKnowledge("query",4,context);assertThat(output).startsWith("OK:");
   if(guarded)assertThat(output).contains("核对");else if(empty)assertThat(output).contains("没有找到");
  }
  when(search.retrieve("query",4)).thenReturn(RagResult.legacy(List.of(hit),"query"));
  assertThat(tools.searchKnowledge("query",4,new ToolContext(Map.of(ToolContextKeys.KNOWLEDGE_SOURCES,"invalid callback")))).contains("1 条");
 }
}
