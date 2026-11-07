package com.personalos.calendar;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.dto.*;
import com.personalos.calendar.entity.*;
import com.personalos.calendar.repository.*;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.service.CalendarEventService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CalendarEventBoundaryTest {
 static final Instant AT=Instant.parse("2026-11-07T10:00:00Z");static final UUID ID=UUID.randomUUID();
 static CalendarProperties properties(){return new CalendarProperties(false,1,0,null,null,null,90,100,500);}
 @Test void partialUpdatesPreserveOmittedValuesAndRebuildOnlyPendingReminders(){
  for(Boolean allDay:Arrays.asList(null,false,true))for(boolean times:new boolean[]{false,true})for(Boolean clear:Arrays.asList(null,false,true))for(boolean until:new boolean[]{false,true}) {
   var events=mock(CalendarEventRepository.class);var reminders=mock(ReminderRepository.class);
   var entity=new CalendarEventEntity("old","description","location",false,AT,AT.plusSeconds(3600),RecurrenceType.DAILY,AT.plusSeconds(86400*10),15,AT);
   when(events.findById(ID)).thenReturn(Optional.of(entity));when(events.save(any())).thenAnswer(i->i.getArgument(0));when(reminders.deleteBySourceAndStatus(any(),any(),any())).thenReturn(times?1:0);
   var service=new CalendarEventService(events,reminders,new RecurrenceCalculator(ZoneOffset.UTC),properties(),ZoneOffset.UTC);
   var result=service.update(ID,new UpdateEventRequest(times?" new ":null,times?" ":null,times?" room ":null,allDay,times?AT:null,times?AT.plusSeconds(172800):null,times?RecurrenceType.WEEKLY:null,until?AT.plusSeconds(86400*5):null,clear,times?30:null));
   assertThat(result.title()).isEqualTo(times?"new":"old");assertThat(result.description()).isEqualTo(times?null:"description");assertThat(result.location()).isEqualTo(times?"room":"location");
   assertThat(entity.getRecurrenceUntil()).isEqualTo(until?AT.plusSeconds(86400*5):Boolean.TRUE.equals(clear)?null:AT.plusSeconds(86400*10));
   assertThat(result.startsAt()).isEqualTo(Boolean.TRUE.equals(allDay)?Instant.parse("2026-11-07T00:00:00Z"):AT);
   var order=inOrder(events,reminders);order.verify(events).save(entity);order.verify(reminders).deleteBySourceAndStatus(ReminderSource.CALENDAR_EVENT,ID,ReminderStatus.PENDING);verify(reminders,never()).deleteBySource(any(),any());
  }
 }
 @Test void rejectsHalfIntervalsAndInvalidRangesBeforeWrites(){
  var events=mock(CalendarEventRepository.class);var reminders=mock(ReminderRepository.class);var entity=new CalendarEventEntity("old",null,null,false,AT,AT.plusSeconds(3600),RecurrenceType.NONE,null,15,AT);when(events.findById(ID)).thenReturn(Optional.of(entity));
  var s=new CalendarEventService(events,reminders,new RecurrenceCalculator(ZoneOffset.UTC),properties(),ZoneOffset.UTC);
  for(boolean first:new boolean[]{true,false})assertThatThrownBy(()->s.update(ID,new UpdateEventRequest(null,null,null,null,first?AT:null,first?null:AT,null,null,null,null))).hasMessageContaining("同时提供");
  assertThatThrownBy(()->s.update(ID,new UpdateEventRequest(null,null,null,false,AT,AT.minusSeconds(1),null,null,null,null))).hasMessageContaining("结束时间不能早于");
  assertThatThrownBy(()->s.update(ID,new UpdateEventRequest(null,null,null,false,null,null,null,AT.minusSeconds(1),null,null))).hasMessageContaining("重复截止");
  assertThatThrownBy(()->s.search(AT,AT.minusSeconds(1),0,10)).hasMessageContaining("区间终点");assertThatThrownBy(()->s.search(AT,AT.plusSeconds(86400*91),0,10)).hasMessageContaining("90");
  for(boolean first:new boolean[]{true,false})assertThatThrownBy(()->s.create(new CreateEventRequest("event",null,null,false,first?AT:null,first?null:AT,null,null,null))).hasMessageContaining("不能为空");
  assertThat(s.findByIds(null)).isEmpty();assertThat(s.findByIds(List.of())).isEmpty();verify(events,never()).save(any());verifyNoInteractions(reminders);
 }
 @Test void searchDefaultsAndPagingReturnSortedOccurrences(){
  var events=mock(CalendarEventRepository.class);var s=new CalendarEventService(events,mock(ReminderRepository.class),new RecurrenceCalculator(ZoneOffset.UTC),properties(),ZoneOffset.UTC);
  var later=new CalendarEventEntity("later",null,null,false,AT.plusSeconds(60),AT.plusSeconds(120),RecurrenceType.NONE,null,15,AT);var earlier=new CalendarEventEntity("earlier",null,null,true,AT,AT.plusSeconds(60),RecurrenceType.DAILY,null,15,AT);
  when(events.findCandidates(any(),any(),any())).thenReturn(List.of(later,earlier));
  for(Integer page:Arrays.asList(null,-1,0,1)){var r=s.search(AT,AT.plusSeconds(3600),page,1);assertThat(r.total()).isEqualTo(2);assertThat(r.items().getFirst().title()).isEqualTo(page!=null&&page==1?"later":"earlier");}
  when(events.findCandidates(any(),any(),any())).thenReturn(List.of());assertThat(s.search(null,null,null,null).items()).isEmpty();
 }
 @Test void calendarConfigurationNormalizesInvalidDurationsAndCapsQueries(){
  for(Duration ahead:Arrays.asList(null,Duration.ZERO,Duration.ofSeconds(-1),Duration.ofDays(10)))for(Duration back:Arrays.asList(null,Duration.ZERO,Duration.ofSeconds(-1),Duration.ofHours(2)))for(Duration lead:Arrays.asList(null,Duration.ZERO,Duration.ofMinutes(-1),Duration.ofMinutes(20))){var p=new CalendarProperties(true,-1,-1,ahead,back,lead,-1,-1,501);assertThat(p.scanIntervalMs()).isEqualTo(10000);assertThat(p.scanInitialDelayMs()).isEqualTo(10000);assertThat(p.scanLookahead()).isEqualTo(ahead!=null&&!ahead.isNegative()&&!ahead.isZero()?ahead:Duration.ofHours(24));assertThat(p.scanLookback()).isEqualTo(back!=null&&!back.isNegative()&&!back.isZero()?back:Duration.ofHours(24));assertThat(p.defaultReminderLead()).isEqualTo(lead!=null&&!lead.isNegative()?lead:Duration.ofMinutes(15));assertThat(p.effectiveScanLookahead()).isGreaterThanOrEqualTo(Duration.ofDays(7));for(Integer value:Arrays.asList(null,-1,0,1,1000)){assertThat(p.normalizeRangeDays(value)).isEqualTo(value==null||value<=0?7:Math.min(value,90));assertThat(p.normalizeLimit(value)).isEqualTo(value==null||value<=0?100:Math.min(value,500));}}
  assertThat(new CalendarProperties(true,1,0,Duration.ofDays(8),Duration.ofHours(1),Duration.ZERO,1,1,0).maxQueryLimit()).isEqualTo(500);
 }
}
