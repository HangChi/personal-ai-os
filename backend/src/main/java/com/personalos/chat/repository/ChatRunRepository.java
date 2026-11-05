package com.personalos.chat.repository;

import com.personalos.chat.dto.ChatRunDto;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 短 SQL 事务；模型与外部工具调用均不占数据库事务。 */
@Repository
public class ChatRunRepository {
    private final JdbcTemplate jdbc;
    public ChatRunRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Run(String id, String conversationId, String request, String status) { }
    public void create(String id, String conversationId, String request) {
        jdbc.update("insert into chat_run(id,conversation_id,request,status) values (?,?,?,'RUNNING')",
                id, conversationId, request);
    }
    public Optional<Run> find(String conversationId, String id) {
        return jdbc.query("select * from chat_run where conversation_id=? and id=?",
                (rs, n) -> new Run(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("request"), rs.getString("status")), conversationId, id).stream().findFirst();
    }
    public Optional<Run> latest(String conversationId) {
        return jdbc.query("select * from chat_run where conversation_id=? order by created_at desc limit 1",
                (rs, n) -> new Run(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("request"), rs.getString("status")), conversationId).stream().findFirst();
    }
    public Optional<Run> active(String conversationId) {
        return jdbc.query("select * from chat_run where conversation_id=? and status='RUNNING' order by created_at desc limit 1",
                (rs,n) -> new Run(rs.getString("id"), rs.getString("conversation_id"), rs.getString("request"), rs.getString("status")),
                conversationId).stream().findFirst();
    }
    public boolean runningFor(String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from chat_run where conversation_id=? and status='RUNNING')",
                Boolean.class, conversationId));
    }
    public List<Run> recoveryCandidates() {
        return jdbc.query("select r.* from chat_run r where r.status='RUNNING' or (r.status='WAITING_USER' and not exists "
                + "(select 1 from agent_question q where q.run_id=r.id and q.status='PENDING'))",
                (rs, n) -> new Run(rs.getString("id"), rs.getString("conversation_id"), rs.getString("request"), rs.getString("status")));
    }
    public List<Run> running() {
        return jdbc.query("select * from chat_run where status='RUNNING'",
                (rs, n) -> new Run(rs.getString("id"), rs.getString("conversation_id"),
                        rs.getString("request"), rs.getString("status")));
    }
    public void event(String id, String name, String payload) {
        jdbc.update("insert into chat_run_event(run_id,name,payload) values (?,?,?)", id, name, payload);
    }
    public List<ChatRunDto.Event> events(String id, long after) {
        return jdbc.query("select id,name,payload from chat_run_event where run_id=? and id>? order by id limit 200",
                (rs, n) -> new ChatRunDto.Event(rs.getLong(1), rs.getString(2), rs.getString(3)), id, after);
    }
    public boolean transition(String id, String from, String to) {
        return jdbc.update("update chat_run set status=?,updated_at=now() where id=? and status=?", to, id, from) == 1;
    }
    /** Persist intent with RUNNING, including a crash before worker launch. */
    @org.springframework.transaction.annotation.Transactional
    public boolean beginResume(String id, String from, boolean summaryOnly) {
        if (!transition(id, from, "RUNNING")) return false;
        event(id, "summary_only", Boolean.toString(summaryOnly));
        return true;
    }
    public boolean summaryAttempt(String id) {
        return jdbc.query("select payload from chat_run_event where run_id=? and name='summary_only' order by id desc limit 1",
                (rs, n) -> rs.getString(1), id).stream().anyMatch("true"::equals);
    }
    public boolean uncertain(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from operation_record where turn_id=? and outcome in ('IN_PROGRESS','FAILED'))",
                Boolean.class, id));
    }
    public boolean unfinishedPlan(String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from agent_plan where conversation_id=? and status='ACTIVE')",
                Boolean.class, conversationId));
    }
    public String receipts(String id) {
        return String.join("\n", jdbc.query(
                "select operation,outcome,left(response_payload,800) as payload from operation_record where turn_id=? order by id limit 30",
                (rs, n) -> rs.getString("operation") + " [" + rs.getString("outcome") + "]: " + rs.getString("payload"), id));
    }
    public boolean pending(String conversationId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from confirmation where conversation_id=? and status='PENDING' and expires_at>now())",
                Boolean.class, conversationId));
    }
}
