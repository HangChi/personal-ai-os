package com.personalos.calendar;

import static org.assertj.core.api.Assertions.*;
import com.personalos.support.ContainerContextTest;
import com.personalos.calendar.service.CalendarExchangeService;
import java.time.*;
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
        var exported=service.exportCalendar();assertThat(service.importCalendar(exported,service.preview(exported).digest()).skipped()).isEqualTo(1);
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
}
