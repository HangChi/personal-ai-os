package com.personalos.calendar;
import static org.assertj.core.api.Assertions.*;
import com.personalos.calendar.service.CalendarIcs;
import com.personalos.calendar.dto.CalendarEventDto;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class CalendarIcsTest {
    private final ZoneId zone=ZoneId.of("Asia/Shanghai");
    private String file(String extra) { return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:test-1\r\nSUMMARY:例会\\,复盘\r\nDTSTART;TZID=Asia/Shanghai:20261106T090000\r\nDTEND;TZID=Asia/Shanghai:20261106T100000\r\n"+extra+"END:VEVENT\r\nEND:VCALENDAR\r\n"; }
    @Test void parsesTimezoneEscapesAndWeekdays() {
        var row=CalendarIcs.parse(file("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\r\n"),zone).getFirst();
        assertThat(row.event().title()).isEqualTo("例会,复盘");
        assertThat(row.event().startsAt()).isEqualTo(Instant.parse("2026-11-06T01:00:00Z"));
        assertThat(row.event().recurrenceType().name()).isEqualTo("WEEKDAYS");
    }
    @Test void rejectsLossyAndMalformedInput() {
        for(String extra:List.of("RRULE:FREQ=DAILY;COUNT=5\r\n","EXDATE:20261107T090000Z\r\n","BEGIN:VALARM\r\nEND:VALARM\r\n")) assertThatThrownBy(()->CalendarIcs.parse(file(extra),zone)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->CalendarIcs.parse(file("").replace("20261106T090000","20260230T090000"),zone)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsParametersAndIncompatibleRecurrence() {
        for(String text:List.of(file("").replace("SUMMARY:","SUMMARY;VALUE=DATE:"),file("RRULE:FREQ=MONTHLY\r\n").replace("20261106","20261031"),file("RRULE:FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR\r\n").replace("20261106","20261107"))) assertThatThrownBy(()->CalendarIcs.parse(text,zone)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->CalendarIcs.parse(file("RRULE:FREQ=DAILY\r\n").replace("Asia/Shanghai","America/New_York"),ZoneId.of("America/New_York"))).isInstanceOf(RuntimeException.class);
    }
    @Test void roundTripsUnicodeFoldingAndDates() {
        var event=new CalendarEventDto(UUID.randomUUID(),"资料".repeat(50),"a\nb,;\\","线上",true,Instant.parse("2026-11-05T16:00:00Z"),Instant.parse("2026-11-06T16:00:00Z"),"NONE",null,15,Instant.now());
        String text=CalendarIcs.write(List.of(event),zone);
        assertThat(text.split("\r\n")).allMatch(line->line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=75);
        var parsed=CalendarIcs.parse(text,zone).getFirst().event();
        assertThat(parsed.title()).isEqualTo(event.title()); assertThat(parsed.description()).isEqualTo(event.description()); assertThat(parsed.endsAt()).isEqualTo(event.endsAt());
    }
}
