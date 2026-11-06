package com.personalos.calendar.service;

import com.personalos.calendar.dto.*;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.*;
import java.util.*;

/** 仅接受可无损映射的 ICS 子集；拒绝例外、复杂规则和邀请，绝不静默丢弃语义。 */
public final class CalendarIcs {
    private CalendarIcs() {}
    public record Entry(String uid, CreateEventRequest event) {}
    private record Property(String name, Map<String,String> parameters, String value) {}
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> ALLOWED = Set.of("UID","DTSTART","DTEND","SUMMARY","DESCRIPTION","LOCATION","RRULE","DTSTAMP","CREATED","LAST-MODIFIED","SEQUENCE","STATUS","TRANSP");
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, "ICS：" + message); }
    public static List<Entry> parse(String content, ZoneId zone) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw invalid("文件不能为空或超过 1MiB");
        String text = content.replace("\r\n", "\n");
        if (text.contains("\r") || text.indexOf('\0') >= 0) throw invalid("非法换行或字符");
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lines.isEmpty()) throw invalid("非法折行");
                int last=lines.size()-1; lines.set(last, lines.get(last)+line.substring(1));
            } else if (!line.isEmpty()) lines.add(line);
        }
        if (lines.size()<3 || !lines.getFirst().equals("BEGIN:VCALENDAR") || !lines.getLast().equals("END:VCALENDAR")) throw invalid("必须是完整 VCALENDAR");
        List<Entry> result = new ArrayList<>(); Set<String> uids = new HashSet<>();
        Map<String,Property> event=null; boolean version=false;
        for (int i=1;i<lines.size()-1;i++) {
            String line=lines.get(i);
            if (line.equals("BEGIN:VEVENT")) { if(event!=null) throw invalid("不支持嵌套组件"); event=new LinkedHashMap<>(); continue; }
            if (line.equals("END:VEVENT")) {
                if(event==null || result.size()>=200) throw invalid("最多 200 个事件");
                Entry entry=entry(event,zone); if(!uids.add(entry.uid())) throw invalid("文件内 UID 重复");
                result.add(entry); event=null; continue;
            }
            if(line.startsWith("BEGIN:") || line.startsWith("END:")) throw invalid("不支持时区定义、提醒、待办或例外组件");
            Property p=property(line);
            if(event==null) {
                if(p.name().equals("VERSION") && p.value().equals("2.0")) version=true;
                else if(!p.name().equals("PRODID") && !p.name().equals("CALSCALE") && !(p.name().equals("METHOD")&&p.value().equals("PUBLISH"))) throw invalid("不支持日历属性 "+p.name());
            } else {
                if(!ALLOWED.contains(p.name()) || event.putIfAbsent(p.name(),p)!=null) throw invalid("不支持或重复的事件属性 "+p.name());
            }
        }
        if(!version || event!=null || result.isEmpty()) throw invalid("缺少 VERSION:2.0 或完整事件");
        return List.copyOf(result);
    }
    private static Property property(String line) {
        int colon=line.indexOf(':'); if(colon<1) throw invalid("属性格式错误");
        String[] key=line.substring(0,colon).split(";",-1); Map<String,String> params=new HashMap<>();
        for(int i=1;i<key.length;i++) {
            String[] pair=key[i].split("=",2);
            if(pair.length!=2 || !Set.of("TZID","VALUE").contains(pair[0]) || params.putIfAbsent(pair[0],pair[1].replace("\"",""))!=null) throw invalid("不支持属性参数");
        }
        if (!params.isEmpty() && !Set.of("DTSTART","DTEND").contains(key[0])) throw invalid("仅开始和结束时间支持属性参数");
        return new Property(key[0],params,line.substring(colon+1));
    }
    private static Entry entry(Map<String,Property> p,ZoneId zone) {
        String uid=required(p,"UID").value();
        if(uid.isBlank() || uid.length()>512 || uid.chars().anyMatch(Character::isISOControl)) throw invalid("UID 无效或超过 512 字符");
        String title=unescape(required(p,"SUMMARY").value()).strip();
        String description=unescape(p.getOrDefault("DESCRIPTION",new Property("",Map.of(),"")).value());
        String location=unescape(p.getOrDefault("LOCATION",new Property("",Map.of(),"")).value());
        if(title.isEmpty() || title.length()>200 || description.length()>2000 || location.length()>200) throw invalid("标题、描述或地点超出长度限制");
        Property start=required(p,"DTSTART"), end=required(p,"DTEND");
        boolean allDay="DATE".equals(start.parameters().get("VALUE"));
        if(allDay!="DATE".equals(end.parameters().get("VALUE"))) throw invalid("开始与结束的日期类型必须一致");
        Instant a=time(start,zone), b=time(end,zone);
        if(!b.isAfter(a) || Duration.between(a,b).compareTo(Duration.ofDays(366))>0) throw invalid("结束必须晚于开始，跨度最多 366 天");
        RecurrenceType type=RecurrenceType.NONE; Instant until=null;
        if(p.containsKey("RRULE")) {
            if(!zone.getId().equals(start.parameters().getOrDefault("TZID",zone.getId())) || !start.parameters().equals(end.parameters()) || start.value().endsWith("Z") != end.value().endsWith("Z")) throw invalid("重复事件的时区须与应用日历一致");
            if(zone.getRules().nextTransition(a)!=null) throw invalid("当前应用时区未来存在夏令时变化，暂不支持交换重复日程");
            Map<String,String> rule=new HashMap<>();
            for(String item:p.get("RRULE").value().split(";")) {
                String[] pair=item.split("=",2);
                if(pair.length!=2 || !Set.of("FREQ","UNTIL","INTERVAL","BYDAY").contains(pair[0]) || rule.putIfAbsent(pair[0],pair[1])!=null) throw invalid("不支持的重复规则");
            }
            if(!rule.getOrDefault("INTERVAL","1").equals("1")) throw invalid("只支持间隔 1 的重复");
            try { type=RecurrenceType.valueOf(rule.getOrDefault("FREQ","")); } catch(IllegalArgumentException ex) { throw invalid("只支持 DAILY/WEEKLY/MONTHLY"); }
            if(!Set.of(RecurrenceType.DAILY,RecurrenceType.WEEKLY,RecurrenceType.MONTHLY).contains(type)) throw invalid("不支持的重复频率");
            if(rule.containsKey("BYDAY")) {
                if(type!=RecurrenceType.WEEKLY || !rule.get("BYDAY").equals("MO,TU,WE,TH,FR")) throw invalid("仅支持周一到周五 BYDAY");
                type=RecurrenceType.WEEKDAYS;
            }
            validateRecurrence(type.name(),a,zone);
            if(rule.containsKey("UNTIL")) {
                if(!allDay && !rule.get("UNTIL").endsWith("Z")) throw invalid("重复截止须使用 UTC");
                until=time(new Property("UNTIL",allDay?Map.of("VALUE","DATE"):Map.of(),rule.get("UNTIL")),zone);
                if(until.isBefore(a)) throw invalid("重复截止早于首次开始");
            }
        }
        if(p.containsKey("STATUS")&&!p.get("STATUS").value().equals("CONFIRMED")) throw invalid("不支持取消或暂定日程");
        if(p.containsKey("TRANSP")&&!p.get("TRANSP").value().equals("OPAQUE")) throw invalid("不支持透明日程");
        return new Entry(uid,new CreateEventRequest(title,description,location,allDay,a,b,type,until,15));
    }
    private static Property required(Map<String,Property> p,String name) { Property v=p.get(name); if(v==null) throw invalid("缺少 "+name); return v; }
    private static Instant time(Property p,ZoneId zone) {
        try {
            if(p.parameters().containsKey("TZID")) zone=ZoneId.of(p.parameters().get("TZID"));
            String value=p.value(), kind=p.parameters().getOrDefault("VALUE","DATE-TIME");
            if(kind.equals("DATE")) {
                if(p.parameters().containsKey("TZID")) throw invalid("DATE 不能带 TZID");
                return LocalDate.parse(value,DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant();
            }
            if(!kind.equals("DATE-TIME")) throw invalid("不支持的时间类型");
            if(value.endsWith("Z")) {
                if(p.parameters().containsKey("TZID")) throw invalid("UTC 时间不能带 TZID");
                return LocalDateTime.parse(value.substring(0,value.length()-1),LOCAL).toInstant(ZoneOffset.UTC);
            }
            LocalDateTime local=LocalDateTime.parse(value,LOCAL);
            if(zone.getRules().getValidOffsets(local).size()!=1) throw invalid("本地时间落在夏令时缺失或重叠区间，请使用 UTC");
            return local.atZone(zone).toInstant();
        } catch(DateTimeException ex) { throw invalid("无效日期或时区"); }
    }
    private static void validateRecurrence(String type,Instant start,ZoneId zone) {
        var local=start.atZone(zone);
        if(type.equals("MONTHLY") && local.getDayOfMonth()>28) throw invalid("月末29..31日的重复规则无法无损交换");
        if(type.equals("WEEKDAYS") && local.getDayOfWeek().getValue()>5) throw invalid("工作日重复必须从工作日开始");
        if(!type.equals("NONE") && zone.getRules().nextTransition(start)!=null) throw invalid("当前应用时区未来存在夏令时变化，暂不支持交换重复日程");
    }
    static String escape(String s) { return (s==null?"":s).replace("\\","\\\\").replace("\r","").replace("\n","\\n").replace(";","\\;").replace(",","\\,"); }
    private static String unescape(String s) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c=='\\') { if(++i==s.length()) throw invalid("无效文本转义"); c=s.charAt(i); if(c=='n'||c=='N') c='\n'; else if(c!='\\'&&c!=','&&c!=';') throw invalid("无效文本转义"); }
            out.append(c);
        }
        return out.toString();
    }
    public static String write(List<CalendarEventDto> events,ZoneId zone) {
        if(events.size()>10_000) throw invalid("导出最多 10000 个日程");
        List<String> lines=new ArrayList<>(List.of("BEGIN:VCALENDAR","VERSION:2.0","PRODID:-//Personal AI OS//Calendar//ZH","CALSCALE:GREGORIAN"));
        for(var e:events) {
            validateRecurrence(e.recurrenceType(),e.startsAt(),zone);
            lines.add("BEGIN:VEVENT"); lines.add("UID:"+e.id()+"@personal-ai-os");
            lines.add("DTSTAMP:"+LOCAL.format(e.createdAt().atOffset(ZoneOffset.UTC))+"Z");
            for(boolean start:List.of(true,false)) {
                Instant t=start?e.startsAt():e.endsAt(); String key=start?"DTSTART":"DTEND";
                lines.add(e.allDay()?key+";VALUE=DATE:"+DateTimeFormatter.BASIC_ISO_DATE.format(t.atZone(zone).toLocalDate()):(e.recurrenceType().equals("NONE")?key+":"+LOCAL.format(t.atOffset(ZoneOffset.UTC))+"Z":key+";TZID="+zone.getId()+":"+LOCAL.format(t.atZone(zone))));
            }
            lines.add("SUMMARY:"+escape(e.title())); lines.add("DESCRIPTION:"+escape(e.description())); lines.add("LOCATION:"+escape(e.location()));
            if(!e.recurrenceType().equals("NONE")) {
                String rule=e.recurrenceType().equals("WEEKDAYS")?"FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR":"FREQ="+e.recurrenceType();
                if(e.recurrenceUntil()!=null) rule+=";UNTIL="+(e.allDay()?DateTimeFormatter.BASIC_ISO_DATE.format(e.recurrenceUntil().atZone(zone).toLocalDate()):LOCAL.format(e.recurrenceUntil().atOffset(ZoneOffset.UTC))+"Z");
                lines.add("RRULE:"+rule);
            }
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR"); StringBuilder out=new StringBuilder();
        for(String line:lines) {
            int bytes=0;
            for(int cp:line.codePoints().toArray()) {
                String value=new String(Character.toChars(cp)); int n=value.getBytes(StandardCharsets.UTF_8).length;
                if(bytes+n>75) { out.append("\r\n "); bytes=1; }
                out.append(value); bytes+=n;
            }
            out.append("\r\n");
        }
        return out.toString();
    }
}
