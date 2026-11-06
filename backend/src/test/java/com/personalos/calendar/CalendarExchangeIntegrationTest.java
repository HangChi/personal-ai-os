package com.personalos.calendar;

import static org.assertj.core.api.Assertions.*;
import com.personalos.support.ContainerContextTest;
import com.personalos.calendar.service.CalendarExchangeService;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties={"spring.ai.model.chat=none","app.calendar.scan-enabled=false"})
@Testcontainers
class CalendarExchangeIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
    private final CalendarExchangeService service;
    private final JdbcTemplate jdbc;
    @Autowired CalendarExchangeIntegrationTest(CalendarExchangeService service,JdbcTemplate jdbc) { this.service=service;this.jdbc=jdbc; }
    @BeforeEach void clean() { jdbc.execute("TRUNCATE calendar_event CASCADE"); }
    private String file(String uid,String start,String end) { return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:"+uid+"\r\nSUMMARY:测试\r\nDTSTART:"+start+"\r\nDTEND:"+end+"\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"; }
    @Test void previewDigestConcurrencyAndRoundTrip() throws Exception {
        String text=file("external-1","20261106T010000Z","20261106T020000Z");var preview=service.preview(text);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Integer.class)).isZero();
        assertThatThrownBy(()->service.importCalendar(text,"stale")).isInstanceOf(RuntimeException.class);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->service.importCalendar(text,preview.digest()));var two=pool.submit(()->service.importCalendar(text,preview.digest()));
            assertThat(one.get().created()+two.get().created()).isEqualTo(1);
        }
        var exported=service.exportCalendar();assertThat(exported).contains("UID:external-1\r\n");assertThat(service.importCalendar(exported,service.preview(exported).digest()).skipped()).isEqualTo(1);
        assertThat(service.preview(text).duplicateCount()).isEqualTo(1);
        String invalid=text.replace("END:VCALENDAR", "BEGIN:VEVENT\r\nUID:bad\r\nSUMMARY:bad\r\nDTSTART:invalid\r\nDTEND:invalid\r\nEND:VEVENT\r\nEND:VCALENDAR");
        assertThatThrownBy(()->service.preview(invalid)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Integer.class)).isEqualTo(1);
    }
    @Test void conflictsIncludeEarlierStartsButExcludeAdjacentEndpoints() {
        for(var interval:new String[][]{{"long","20261105T010000Z","20261106T013000Z"},{"overlap","20261106T010000Z","20261106T020000Z"},{"adjacent","20261106T020000Z","20261106T030000Z"}}) {
            String text=file(interval[0],interval[1],interval[2]);service.importCalendar(text,service.preview(text).digest());
        }
        assertThat(service.conflicts(Instant.parse("2026-11-06T00:00:00Z"),Instant.parse("2026-11-07T00:00:00Z")).total()).isEqualTo(1);
        assertThatThrownBy(()->service.conflicts(Instant.EPOCH,Instant.EPOCH.plus(Duration.ofDays(31)).plusMillis(1))).isInstanceOf(RuntimeException.class);
    }
    @Test void importedAndLocalUidsSurviveAnotherInstancesRoundTrip() {
        String uid="来源-"+"x".repeat(100)+"@calendar.example";
        String text=file(uid,"20261106T010000Z","20261106T020000Z");
        service.importCalendar(text,service.preview(text).digest());
        jdbc.execute("INSERT INTO calendar_event(title,starts_at,ends_at) VALUES('本地日程','2026-11-06T03:00Z','2026-11-06T04:00Z')");
        UUID local=jdbc.queryForObject("SELECT id FROM calendar_event WHERE title='本地日程'",UUID.class);
        String exported=service.exportCalendar();
        var expected=Set.of(uid,local+"@personal-ai-os");
        assertThat(com.personalos.calendar.service.CalendarIcs.parse(exported,ZoneId.of("Asia/Shanghai")).stream().map(com.personalos.calendar.service.CalendarIcs.Entry::uid).toList()).containsExactlyInAnyOrderElementsOf(expected);
        // 清空后导入模拟另一实例，其本地ID会变化，来源UID仍须完整保留。
        jdbc.execute("TRUNCATE calendar_event CASCADE");
        assertThat(service.importCalendar(exported,service.preview(exported).digest()).created()).isEqualTo(2);
        String returned=service.exportCalendar();
        assertThat(com.personalos.calendar.service.CalendarIcs.parse(returned,ZoneId.of("Asia/Shanghai")).stream().map(com.personalos.calendar.service.CalendarIcs.Entry::uid).toList()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(service.importCalendar(returned,service.preview(returned).digest()).skipped()).isEqualTo(2);
    }
}
