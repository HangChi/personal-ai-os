package com.personalos.agent.interaction.repository;

import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.dto.AgentFollowup;
import com.personalos.chat.dto.StreamChatRequest;
import com.personalos.common.json.JsonCodec;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AgentInteractionRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public AgentInteractionRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }
    public Optional<AgentQuestion> question(String runId, String id) {
        return questions("select * from agent_question where run_id=? and id=?", runId, id).stream().findFirst();
    }
    public Optional<AgentQuestion> pendingQuestion(String runId) {
        return questions("select * from agent_question where run_id=? and status='PENDING'", runId).stream().findFirst();
    }
    public Optional<AgentQuestion> byDigest(String runId, String digest) {
        return questions("select * from agent_question where run_id=? and digest=?", runId, digest).stream().findFirst();
    }
    private List<AgentQuestion> questions(String sql, Object... args) {
        return jdbc.query(sql, (rs, n) -> new AgentQuestion(rs.getString("id"), rs.getString("run_id"),
                rs.getString("question"), List.of(json.fromJson(rs.getString("options"), AgentQuestion.Option[].class)),
                rs.getString("status"), rs.getString("answer")), args);
    }
    public void createQuestion(AgentQuestion question, String digest) {
        jdbc.update("insert into agent_question(id,run_id,question,options,digest) values (?,?,?,?,?)",
                question.id(), question.runId(), question.question(), json.toJson(question.options()), digest);
    }
    public void answer(String runId, String id, String answer) {
        jdbc.update("update agent_question set answer=?,status='ANSWERED' where run_id=? and id=? and status='PENDING'",
                answer, runId, id);
    }
    public void createFollowup(AgentFollowup input) {
        jdbc.update("insert into agent_followup(id,run_id,conversation_id,mode,request) values (?,?,?,?,?)",
                input.id(), input.runId(), input.conversationId(), input.mode(), json.toJson(input.request()));
    }
    public Optional<AgentFollowup> followup(String conversationId, String id) {
        return inputs("select * from agent_followup where conversation_id=? and id=?", conversationId, id).stream().findFirst();
    }
    public List<AgentFollowup> pendingInputs(String conversationId) {
        return inputs("select * from agent_followup where conversation_id=? and status='PENDING' order by seq", conversationId);
    }
    public List<AgentFollowup> pendingSteering(String runId) {
        return inputs("select * from agent_followup where run_id=? and mode='STEER' and status='PENDING' order by seq", runId);
    }
    public boolean edit(String conversationId, String id, StreamChatRequest request) {
        return jdbc.update("update agent_followup set request=? where conversation_id=? and id=? and status='PENDING' and mode='QUEUE'",
                json.toJson(request), conversationId, id) == 1;
    }
    public boolean steer(String conversationId, String id, String runId) {
        return jdbc.update("update agent_followup set mode='STEER',run_id=? where conversation_id=? and id=? and status='PENDING' and mode='QUEUE'",
                runId, conversationId, id) == 1;
    }
    public void status(String id, String status) {
        jdbc.update("update agent_followup set status=? where id=? and status='PENDING'", status, id);
    }
    public List<String> queuedConversations() {
        return jdbc.queryForList("select distinct conversation_id from agent_followup where mode='QUEUE' and status='PENDING'", String.class);
    }
    public String transcript(String runId) {
        var questions = jdbc.query("select question,answer from agent_question where run_id=? and status='ANSWERED' order by created_at",
                (rs,n) -> "问题：" + rs.getString(1) + "\n用户回答：" + rs.getString(2), runId);
        var steering = inputs("select * from agent_followup where run_id=? and mode='STEER' and status='APPLIED' order by seq", runId)
                .stream().map(input -> "用户补充要求：" + input.request().content()).toList();
        return String.join("\n", questions) + "\n" + String.join("\n", steering);
    }
    private List<AgentFollowup> inputs(String sql, Object... args) {
        return jdbc.query(sql, (rs,n) -> new AgentFollowup(rs.getString("id"), rs.getString("run_id"), rs.getString("conversation_id"),
                rs.getString("mode"), json.fromJson(rs.getString("request"), StreamChatRequest.class), rs.getString("status")), args);
    }
}
