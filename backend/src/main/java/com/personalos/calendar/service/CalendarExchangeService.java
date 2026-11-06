package com.personalos.calendar.service;

import com.personalos.calendar.dto.*;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CalendarExchangeService {
    private final CalendarEventService events;
    private final JdbcTemplate jdbc;
    private final ZoneId zone;
    private final jakarta.persistence.EntityManager entityManager;
    public CalendarExchangeService(CalendarEventService events,JdbcTemplate jdbc,ZoneId zone,jakarta.persistence.EntityManager entityManager) { this.events=events;this.jdbc=jdbc;this.zone=zone;this.entityManager=entityManager; }
    public record Preview(String digest,List<CalendarIcs.Entry> events,int duplicateCount) {}
    public record Imported(int created,int skipped) {}
    public record Conflict(CalendarOccurrenceDto first,CalendarOccurrenceDto second) {}
    public record Conflicts(List<Conflict> items,int total,boolean truncated) {}
    static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private boolean exists(String uid) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM calendar_ics_identity WHERE uid=?)",Boolean.class,uid))) return true;
        if(uid.endsWith("@personal-ai-os")) {
            try { UUID id=UUID.fromString(uid.substring(0,uid.indexOf('@'))); return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM calendar_event WHERE id=?)",Boolean.class,id)); }
            catch(IllegalArgumentException ignored) { return false; }
        }
        return false;
    }
    @Transactional(readOnly=true)
    public Preview preview(String content) {
        var parsed=CalendarIcs.parse(content,zone);
        return new Preview(digest(content),parsed,(int)parsed.stream().filter(e->exists(e.uid())).count());
    }
    @Transactional
    public Imported importCalendar(String content,String expectedDigest) {
        var parsed=CalendarIcs.parse(content,zone);
        if(!digest(content).equals(expectedDigest)) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"文件内容已变化，请重新预览");
        jdbc.execute("SELECT pg_advisory_xact_lock(350044)");
        int created=0,skipped=0;
        for(var e:parsed) {
            if(exists(e.uid())) { skipped++; continue; }
            var saved=events.create(e.event());
            entityManager.flush(); // JPA 新日程必须先落盘，才能建立 JDBC 来源外键。
            jdbc.update("INSERT INTO calendar_ics_identity(uid,event_id) VALUES(?,?)",e.uid(),saved.id()); created++;
        }
        return new Imported(created,skipped);
    }
    @Transactional(readOnly=true)
    public String exportCalendar() { bounded(); return CalendarIcs.write(events.listAll(),zone); }
    private void bounded() { if(jdbc.queryForObject("SELECT count(*) FROM calendar_event",Long.class)>10000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"日程超过 10000 条"); }
    @Transactional(readOnly=true)
    public Conflicts conflicts(Instant from,Instant to) {
        if(from==null || to==null || !to.isAfter(from) || Duration.between(from,to).compareTo(Duration.ofDays(31))>0) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"冲突查询须提供最多 31 天的有效区间");
        bounded();
        List<CalendarOccurrenceDto> all=new ArrayList<>();
        var calculator=new RecurrenceCalculator(zone);
        for(var event:events.listAll()) {
            var duration=Duration.between(event.startsAt(),event.endsAt());
            if(duration.compareTo(Duration.ofDays(366))>0) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"存在跨年超长日程，请缩短该日程后查询冲突");
            if(duration.isNegative() || duration.isZero()) continue;
            for(var start:calculator.occurrences(RecurrenceType.valueOf(event.recurrenceType()),event.startsAt(),event.recurrenceUntil(),from.minus(duration),to)) {
                if(start.isBefore(to)&&start.plus(duration).isAfter(from)) all.add(new CalendarOccurrenceDto(event.id(),event.title(),event.location(),event.allDay(),start,start.plus(duration),event.recurrenceType(),!event.recurrenceType().equals("NONE"),event.reminderLeadMinutes()));
                if(all.size()>1000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"该窗口日程过多，请缩小查询范围");
            }
        }
        var rows=all.stream().sorted(Comparator.comparing(CalendarOccurrenceDto::startsAt)).toList();
        List<Conflict> pairs=new ArrayList<>(); int total=0;
        for(int i=0;i<rows.size();i++) for(int j=i+1;j<rows.size()&&rows.get(j).startsAt().isBefore(rows.get(i).endsAt());j++) {
            total++; if(pairs.size()<100) pairs.add(new Conflict(rows.get(i),rows.get(j)));
        }
        return new Conflicts(pairs,total,total>pairs.size());
    }
}
