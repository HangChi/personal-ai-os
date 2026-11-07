package com.personalos.calendar.recurrence;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import com.personalos.calendar.entity.RecurrenceType;
class RecurrenceBoundaryTest {
 @Test void invalidAndEmptyIntervalsNeverProduceOccurrences(){
  var s=new RecurrenceCalculator(ZoneOffset.UTC);var start=Instant.parse("2000-01-01T00:00:00Z");
  assertThat(s.occurrences(null,start,null,null,start)).isEmpty();assertThat(s.occurrences(null,start,null,start,null)).isEmpty();
  assertThat(s.occurrences(null,start,null,start.plusSeconds(1),start)).isEmpty();assertThat(s.occurrences(null,null,null,start,start)).isEmpty();
  assertThat(s.occurrences(null,start,null,start.minusSeconds(2),start.minusSeconds(1))).isEmpty();
  assertThat(s.occurrences(null,start,start.minusSeconds(1),start,start)).isEmpty();
  assertThat(s.occurrences(null,start,null,start,start)).containsExactly(start);assertThat(s.occurrences(null,start,null,start.plusSeconds(1),start.plusSeconds(2))).isEmpty();
 }
 @Test void recurrenceCapsAndInclusiveUntilApplyToEverySteppedRule(){
  var s=new RecurrenceCalculator(ZoneOffset.UTC);var start=Instant.parse("2000-01-01T00:00:00Z");
  for(var type:new RecurrenceType[]{RecurrenceType.DAILY,RecurrenceType.WEEKLY,RecurrenceType.WEEKDAYS,RecurrenceType.MONTHLY}){
   assertThat(s.occurrences(type,start,start,start,start.plus(Duration.ofDays(50)))).hasSize(type==RecurrenceType.WEEKDAYS?0:1);
   var values=s.occurrences(type,start,null,start,start.atZone(ZoneOffset.UTC).plusYears(3000).toInstant());
   assertThat(values.size()).isBetween(1,20000);assertThat(values).isSorted();
  }
 }
}
