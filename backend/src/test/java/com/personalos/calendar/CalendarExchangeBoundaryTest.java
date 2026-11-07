package com.personalos.calendar;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.service.*;
import com.personalos.common.exception.BusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class CalendarExchangeBoundaryTest {
 CalendarEventDto event(Instant start,Duration duration,String repeat){
  return new CalendarEventDto(UUID.randomUUID(),"event",null,null,false,start,start.plus(duration),repeat,null,0,start);
 }
 @Test void rejectsInvalidWindowsCountsAndPathologicalDurations(){
  var events=mock(CalendarEventService.class);var jdbc=mock(JdbcTemplate.class);
  var service=new CalendarExchangeService(events,jdbc,ZoneId.of("UTC"),mock(jakarta.persistence.EntityManager.class));
  var start=Instant.parse("2026-11-07T00:00:00Z");
  for(Instant[] window:new Instant[][]{{null,start},{start,null},{start,start},{start,start.minusSeconds(1)},{start,start.plus(Duration.ofDays(32))}})
   assertThatThrownBy(()->service.conflicts(window[0],window[1])).isInstanceOf(BusinessException.class);
  when(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Long.class)).thenReturn(10001L);
  assertThatThrownBy(service::exportCalendar).hasMessageContaining("10000");
  when(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Long.class)).thenReturn(1L);
  when(events.listAll()).thenReturn(List.of(event(start,Duration.ofDays(367),"NONE")));
  assertThatThrownBy(()->service.conflicts(start,start.plusSeconds(3600))).hasMessageContaining("跨年");
  when(events.listAll()).thenReturn(List.of(event(start,Duration.ZERO,"NONE"),event(start,Duration.ofSeconds(-1),"NONE"),
    event(start.minusSeconds(3600),Duration.ofHours(1),"NONE"),event(start.plusSeconds(3600),Duration.ofHours(1),"NONE")));
  assertThat(service.conflicts(start,start.plusSeconds(3600)).total()).isZero();
 }
 @Test void boundsOverlapPairsAndOccurrenceExpansion(){
  var events=mock(CalendarEventService.class);var jdbc=mock(JdbcTemplate.class);
  var service=new CalendarExchangeService(events,jdbc,ZoneId.of("UTC"),mock(jakarta.persistence.EntityManager.class));
  when(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Long.class)).thenReturn(1000L);
  var start=Instant.parse("2026-11-07T00:00:00Z");var list=new ArrayList<CalendarEventDto>();
  for(int i=0;i<16;i++)list.add(event(start,Duration.ofHours(1),"NONE"));
  when(events.listAll()).thenReturn(list);var conflicts=service.conflicts(start,start.plusSeconds(7200));
  assertThat(conflicts.total()).isEqualTo(120);assertThat(conflicts.items()).hasSize(100);assertThat(conflicts.truncated()).isTrue();
  list.clear();for(int i=0;i<40;i++)list.add(event(start,Duration.ofHours(1),"DAILY"));
  assertThatThrownBy(()->service.conflicts(start,start.plus(Duration.ofDays(30)))).hasMessageContaining("过多");
 }
}
