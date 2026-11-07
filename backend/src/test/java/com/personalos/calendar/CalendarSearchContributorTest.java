package com.personalos.calendar;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.personalos.calendar.entity.*;
import com.personalos.calendar.repository.CalendarEventRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
class CalendarSearchContributorTest {
 @Test void identifiesTitleLocationAndDescriptionAndAllRecurrences(){
  var repo=mock(CalendarEventRepository.class);var contributor=new CalendarSearchContributor(repo);
  var items=new ArrayList<CalendarEventEntity>();
  for(var type:RecurrenceType.values()){
   var event=mock(CalendarEventEntity.class);when(event.getId()).thenReturn(UUID.randomUUID());
   when(event.getTitle()).thenReturn(type==RecurrenceType.NONE?"Needle title":"other");
   when(event.getLocation()).thenReturn(type==RecurrenceType.DAILY?"NEEDLE room":type==RecurrenceType.WEEKLY?"other":null);
   when(event.getRecurrenceType()).thenReturn(type);when(event.getStartsAt()).thenReturn(Instant.EPOCH);items.add(event);
  }
  when(repo.findAll(any(Specification.class),any(Pageable.class))).thenReturn(new PageImpl<>(items));
  var hits=contributor.search("needle",20);
  assertThat(hits).hasSize(items.size());
  assertThat(hits).extracting(h->h.matchedField()).contains("标题","地点","描述");
  assertThat(hits).extracting(h->h.subtitle()).anyMatch(s->s.contains("每天")).anyMatch(s->s.contains("每周")).anyMatch(s->s.contains("每月")).anyMatch(s->s.contains("工作日"));
  assertThat(contributor.moduleName()).isEqualTo("日程");assertThat(contributor.kind()).isEqualTo("event");
 }
}
