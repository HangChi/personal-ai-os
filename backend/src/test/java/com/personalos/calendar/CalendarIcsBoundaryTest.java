package com.personalos.calendar;
import static org.assertj.core.api.Assertions.*;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.service.CalendarIcs;
import com.personalos.common.exception.BusinessException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class CalendarIcsBoundaryTest {
 private static final ZoneId Z=ZoneId.of("Asia/Shanghai");
 private static String file(String extra) {return String.join(System.lineSeparator(),"BEGIN:VCALENDAR","VERSION:2.0","BEGIN:VEVENT","UID:test","SUMMARY:Meeting","DTSTART;TZID=Asia/Shanghai:20261106T090000","DTEND;TZID=Asia/Shanghai:20261106T100000",extra,"END:VEVENT","END:VCALENDAR");}
 private static void reject(String s) {assertThatThrownBy(()->CalendarIcs.parse(s,Z)).isInstanceOf(BusinessException.class).hasMessageContaining("ICS");}
 @Test void envelopeAndProperties() {
  reject(null); reject("x".repeat(1048577)); reject(String.valueOf((char)0)); reject(String.valueOf((char)13)); reject(" folded");
  for(String s:List.of("","BEGIN:VCALENDAR",file("").replace("BEGIN:VCALENDAR","INVALID"),file("").replace("END:VCALENDAR","INVALID"),file("").replace("VERSION:2.0","VERSION:1.0"),file("").replace("VERSION:2.0","METHOD:REQUEST"),file("").replace("BEGIN:VEVENT",""),file("").replace("END:VEVENT",""),file("BEGIN:VEVENT"),file("END:OTHER"))) reject(s);
  for(String s:List.of("UID:dup","X-UNKNOWN:value","BROKEN",":empty","SUMMARY;VALUE=TEXT:bad","STATUS:TENTATIVE","TRANSP:TRANSPARENT","DESCRIPTION:"+"x".repeat(2001),"LOCATION:"+"x".repeat(201))) reject(file(s));
  for(String key:List.of("UID:test","SUMMARY:Meeting","DTSTART;TZID=Asia/Shanghai:20261106T090000","DTEND;TZID=Asia/Shanghai:20261106T100000")) reject(file("").replace(key,""));
  for(String uid:List.of("","x".repeat(513),"a"+(char)9)) reject(file("").replace("UID:test","UID:"+uid));
  for(String title:List.of("  ","x".repeat(201))) reject(file("").replace("SUMMARY:Meeting","SUMMARY:"+title));
  for(String param:List.of("BAD;","UNKNOWN=x;","TZID=UTC;")) reject(file("").replace("DTSTART;","DTSTART;"+param));
  String sep=System.lineSeparator();
  String event=file("").split("BEGIN:VEVENT")[1].split("END:VCALENDAR")[0];
  reject(file("").replace("END:VCALENDAR","BEGIN:VEVENT"+event+"END:VCALENDAR"));
  StringBuilder many=new StringBuilder("BEGIN:VCALENDAR"+sep+"VERSION:2.0"+sep);
  for(int i=0;i<201;i++) many.append("BEGIN:VEVENT").append(event.replace("UID:test","UID:id-"+i));
  reject(many.append("END:VCALENDAR").toString());
  assertThat(CalendarIcs.parse(file("STATUS:CONFIRMED"+sep+"TRANSP:OPAQUE").replace("VERSION:2.0",String.join(sep,"VERSION:2.0","PRODID:test","CALSCALE:GREGORIAN","METHOD:PUBLISH")),Z)).hasSize(1);
 }
 @Test void timeAndRecurrenceValidation() {
  for(String s:List.of(file("").replace("T100000","T090000"),file("").replace("20261106T100000","20281106T100000"),file("").replace("20261106T090000","20260230T090000"),file("").replace("Asia/Shanghai","Invalid/Zone"),file("").replace("DTSTART;TZID=Asia/Shanghai:20261106T090000","DTSTART;VALUE=DATE:20261106"),file("").replace("DTSTART;","DTSTART;VALUE=PERIOD;"),file("").replace("T090000","T090000Z"),file("").replace("TZID=Asia/Shanghai:20261106T090000","VALUE=DATE;TZID=Asia/Shanghai:20261106"))) reject(s);
  for(String local:List.of("20260308T023000","20261101T013000")) reject(file("").replace("Asia/Shanghai","America/New_York").replace("20261106T090000",local));
  for(String rule:List.of("FREQ=YEARLY","FREQ=NONE","UNTIL=20261107T090000Z","FREQ=DAILY;INTERVAL=2","FREQ=DAILY;FREQ=WEEKLY","FREQ=DAILY;BAD=x","FREQ=DAILY;BROKEN","FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR","FREQ=WEEKLY;BYDAY=MO","FREQ=DAILY;UNTIL=20261107T090000","FREQ=DAILY;UNTIL=20261101T000000Z")) reject(file("RRULE:"+rule));
  reject(file("RRULE:FREQ=DAILY").replace("DTSTART;TZID=Asia/Shanghai","DTSTART;TZID=UTC"));
  reject(file("RRULE:FREQ=DAILY").replace("DTEND;TZID=Asia/Shanghai","DTEND"));
  reject(file("RRULE:FREQ=DAILY").replace(";TZID=Asia/Shanghai","").replace("T100000","T100000Z"));
  var utc=CalendarIcs.parse(file("").replace(";TZID=Asia/Shanghai","").replace("T090000","T090000Z").replace("T100000","T100000Z"),Z).getFirst();
  assertThat(utc.event().startsAt()).isEqualTo(Instant.parse("2026-11-06T09:00:00Z"));
  assertThat(CalendarIcs.parse(file("").replace(";TZID=Asia/Shanghai",""),Z).getFirst().event().startsAt()).isEqualTo(Instant.parse("2026-11-06T01:00:00Z"));
 }
 @Test void supportedExportsRoundTrip() {
  for(boolean day:List.of(false,true)) for(String type:List.of("NONE","DAILY","WEEKLY","MONTHLY","WEEKDAYS")) for(boolean cutoff:List.of(false,true)) {
   Instant start=Instant.parse(day?"2026-11-05T16:00:00Z":"2026-11-06T01:00:00Z");
   var id=UUID.randomUUID(); var e=new CalendarEventDto(id,"Meeting","description",null,day,start,start.plusSeconds(day?86400:3600),type,cutoff?start.plusSeconds(604800):null,15,start);
   var row=CalendarIcs.parse(CalendarIcs.write(List.of(e),Z,Map.of(id,"original-id")),Z).getFirst();
   assertThat(row.uid()).isEqualTo("original-id"); assertThat(row.event().startsAt()).isEqualTo(start); assertThat(row.event().endsAt()).isEqualTo(e.endsAt());
   assertThat(row.event().allDay()).isEqualTo(day); assertThat(row.event().recurrenceType().name()).isEqualTo(type);
   assertThat(row.event().recurrenceUntil()).isEqualTo(type.equals("NONE")?null:e.recurrenceUntil());
  }
  var start=Instant.parse("2026-11-06T01:00:00Z"); var e=new CalendarEventDto(UUID.randomUUID(),"a","b","c",false,start,start.plusSeconds(60),"NONE",null,15,start);
  assertThatThrownBy(()->CalendarIcs.write(Collections.nCopies(10001,e),Z)).isInstanceOf(BusinessException.class);
  assertThat(CalendarIcs.write(List.of(e),Z)).contains(e.id()+"@personal-ai-os");
  for(String type:List.of("MONTHLY","WEEKDAYS","DAILY")) {
   var invalid=Instant.parse(type.equals("MONTHLY")?"2026-10-31T01:00:00Z":"2026-11-07T01:00:00Z"); var bad=new CalendarEventDto(UUID.randomUUID(),"a","b","c",false,invalid,invalid.plusSeconds(60),type,null,15,invalid);
   assertThatThrownBy(()->CalendarIcs.write(List.of(bad),type.equals("DAILY")?ZoneId.of("America/New_York"):Z)).isInstanceOf(BusinessException.class);
  }
 }

 @Test void foldedLinesDateTimezoneAndInvalidTextEscapesAreRejected(){
  reject(String.valueOf((char)9)+"fold");
  reject(file("").replace("VERSION:2.0",""));
  for(String text:List.of("tail"+(char)92,"bad"+(char)92+"q"))reject(file("DESCRIPTION:"+text));
  String recurring=file("RRULE:FREQ=DAILY").replace("Asia/Shanghai","America/New_York");
  assertThatThrownBy(()->CalendarIcs.parse(recurring,ZoneId.of("America/New_York"))).isInstanceOf(BusinessException.class);
  assertThat(CalendarIcs.parse(file("DESCRIPTION:a"+(char)92+"N"+"b"),Z).getFirst().event().description()).contains(String.valueOf((char)10));
 }
}
