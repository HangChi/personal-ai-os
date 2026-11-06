package com.personalos.workflow.service;
import com.personalos.calendar.service.CalendarEventService;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 固定查询策略；不注入模型、Agent、工具或外部HTTP客户端。 */
@Service
public class ReadOnlyReports {
 private final JdbcTemplate jdbc;private final CalendarEventService calendar;
 public ReadOnlyReports(JdbcTemplate jdbc,CalendarEventService calendar){this.jdbc=jdbc;this.calendar=calendar;}
 @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
 public record Snapshot(String name,String kind,String timezone,@com.fasterxml.jackson.annotation.JsonProperty("max_items") int maxItems,@com.fasterxml.jackson.annotation.JsonProperty("max_output_chars") int maxOutputChars){}
 public String generate(Snapshot s,Instant scheduled){
  ZoneId zone=ZoneId.of(s.timezone());LocalDate day=scheduled.atZone(zone).toLocalDate();boolean weekly=s.kind().equals("WEEKLY_REVIEW");
  Instant from=(weekly?day.minusDays(7):day).atStartOfDay(zone).toInstant(),to=(weekly?day:day.plusDays(1)).atStartOfDay(zone).toInstant();
  List<String> lines=new ArrayList<>();lines.add(s.name()+"（固定只读报告）");lines.add("时区："+s.timezone()+"；范围：["+from+", "+to+")");lines.add("零模型调用、零工具调用；数据项上限 "+s.maxItems()+"，输出字符上限 "+s.maxOutputChars());
  List<String> priority=new ArrayList<>(),secondary=new ArrayList<>();
  if(s.kind().equals("KNOWLEDGE_REVIEW")) {
   lines.add("资料巡检：仅检查本地资料和采集状态，不访问来源网页。");
   secondary.addAll(jdbc.query("SELECT title,created_at FROM knowledge_document ORDER BY created_at DESC,id LIMIT ?",(rs,n)->"资料："+rs.getString(1)+" · 入库时间 "+rs.getObject(2),s.maxItems()+1));
   priority.addAll(jdbc.query("SELECT canonical_url,last_error,pending_job_id FROM knowledge_capture WHERE last_error IS NOT NULL OR pending_job_id IS NOT NULL ORDER BY (last_error IS NOT NULL) DESC,last_checked_at DESC NULLS LAST,id LIMIT ?",(rs,n)->"采集："+rs.getString(1)+" · "+(rs.getString(2)==null?"正在索引":rs.getString(2)),s.maxItems()+1));
   lines.add("按采集状态、资料分区轮流展示，采集失败优先；空余名额自动补足。");
  } else {
   secondary.addAll(jdbc.query(weekly?"SELECT title,completed_at FROM task WHERE status='DONE' AND completed_at>=? AND completed_at<? ORDER BY completed_at,id LIMIT ?":"SELECT title,due_at FROM task WHERE status<>'DONE' AND due_at<? ORDER BY due_at,id LIMIT ?",(rs,n)->(weekly?"已完成：":"待办：")+rs.getString(1)+" · "+rs.getObject(2),weekly?new Object[]{java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),s.maxItems()+1}:new Object[]{java.sql.Timestamp.from(to),s.maxItems()+1}));
   var occurrences=calendar.search(from,to,0,Math.min(101,s.maxItems()+1));
   for(var event:occurrences.items()) if(event.startsAt().isBefore(to))priority.add("日程："+event.title()+" · "+event.startsAt().atZone(zone)+" — "+event.endsAt().atZone(zone));
   lines.add("按日程、任务分区轮流展示，日程优先；空余名额自动补足。");
   if(occurrences.total()>occurrences.items().size())lines.add("日程候选已按上限截断。");
  }
  if(priority.isEmpty()&&secondary.isEmpty())lines.add("该范围暂无匹配记录。");
  int shown=0;
  for(int i=0;i<Math.max(priority.size(),secondary.size())&&shown<s.maxItems();i++) {
   if(i<priority.size()&&shown<s.maxItems()){lines.add("• "+priority.get(i));shown++;}
   if(i<secondary.size()&&shown<s.maxItems()){lines.add("• "+secondary.get(i));shown++;}
  }
  if(priority.size()+secondary.size()>s.maxItems())lines.add("[达到数据项上限，部分记录未展示]");
  String report=String.join("\n",lines),suffix="\n[达到输出上限，部分内容已截断]";
  if(report.codePointCount(0,report.length())>s.maxOutputChars())report=report.substring(0,report.offsetByCodePoints(0,s.maxOutputChars()-suffix.codePointCount(0,suffix.length())))+suffix;
  return report;
 }
}
