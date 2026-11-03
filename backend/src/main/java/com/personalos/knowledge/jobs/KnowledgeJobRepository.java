package com.personalos.knowledge.jobs;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeJobRepository {
    private final JdbcTemplate jdbc;
    public KnowledgeJobRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Job(UUID id, UUID documentId, boolean replacing, String filename, String title,
                      String status, int attempts, int progress, String stage, String result, String error) { }
    public record Claimed(Job job, UUID token, byte[] payload) { }
    private Job map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Job(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getBoolean("replacing"),
                rs.getString("filename"), rs.getString("title"), rs.getString("status"), rs.getInt("attempts"),
                rs.getInt("progress"), rs.getString("stage"), rs.getString("result"), rs.getString("error"));
    }
    public List<Job> recent() { return jdbc.query("select * from knowledge_index_job order by created_at desc limit 20", (rs,n) -> map(rs)); }
    public Optional<Job> find(UUID id) { return jdbc.query("select * from knowledge_index_job where id=?", (rs,n)->map(rs),id).stream().findFirst(); }
    public String digest(UUID id) { return jdbc.queryForObject("select digest from knowledge_index_job where id=?", String.class,id); }
    public boolean hasReady() { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from knowledge_index_job where "
            + "(status='PENDING' and available_at<=now()) or (status='RUNNING' and lease_until<now()))",Boolean.class)); }
    public double oldestReadySeconds() {
        return jdbc.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM now()-min(available_at)),0) "
                + "FROM knowledge_index_job WHERE status='PENDING' AND available_at<=now()", Double.class);
    }
    public long waitingCount() { return jdbc.queryForObject("select count(*) from knowledge_index_job where status in ('PENDING','RUNNING')",Long.class); }
    public void create(UUID id, UUID document, boolean replacing, String filename, String title, byte[] bytes, String digest) {
        jdbc.update("insert into knowledge_index_job(id,document_id,replacing,filename,title,payload,digest) values (?,?,?,?,?,?,?)",
                id,document,replacing,filename,title,bytes,digest);
    }
    public Optional<Claimed> claim() {
        jdbc.update("update knowledge_index_job set status='FAILED',payload=null,error='执行重试次数已达上限',stage='执行失败',updated_at=now() "
                + "where status='RUNNING' and lease_until<now() and attempts>=3");
        UUID token=UUID.randomUUID();
        return jdbc.query("with candidate as (select id from knowledge_index_job where attempts<3 and "
                + "((status='PENDING' and available_at<=now()) or (status='RUNNING' and lease_until<now())) "
                + "order by created_at for update skip locked limit 1) "
                + "update knowledge_index_job j set status='RUNNING',attempts=attempts+1,claim_token=?,lease_until=now()+interval '60 seconds',"
                + "progress=5,stage='准备文档',updated_at=now() from candidate c where j.id=c.id returning j.*",
                (rs,n)->new Claimed(map(rs),token,rs.getBytes("payload")),token).stream().findFirst();
    }
    public boolean renew(Claimed job) { return jdbc.update("update knowledge_index_job set lease_until=now()+interval '60 seconds' "
            + "where id=? and claim_token=? and status='RUNNING' and lease_until>=now()",job.job().id(),job.token())==1; }
    public void progress(Claimed job,int progress,String stage) { jdbc.update("update knowledge_index_job set progress=?,stage=?,updated_at=now() "
            + "where id=? and claim_token=? and status='RUNNING'",progress,stage,job.job().id(),job.token()); }
    /** 必须在最终写事务内调用，锁定领取身份直到结果一起提交。 */
    public boolean owns(Claimed job) { return !jdbc.queryForList("select id from knowledge_index_job where id=? and claim_token=? "
            + "and status='RUNNING' and lease_until>=now() for update",job.job().id(),job.token()).isEmpty(); }
    public void succeed(Claimed job,String result) { jdbc.update("update knowledge_index_job set status='SUCCEEDED',progress=100,stage='索引完成',"
            + "result=?,error=null,payload=null,lease_until=null,updated_at=now() where id=? and claim_token=?",result,job.job().id(),job.token()); }
    public void fail(Claimed job,boolean retry,String message) { jdbc.update("update knowledge_index_job set status=?,stage=?,error=?,"
            + "payload=case when ? then payload else null end,lease_until=null,available_at=now()+interval '5 seconds',updated_at=now() "
            + "where id=? and claim_token=? and status='RUNNING'",retry?"PENDING":"FAILED",retry?"等待重试":"执行失败",message,retry,job.job().id(),job.token()); }
    public void cleanup() { jdbc.update("delete from knowledge_index_job where status in ('SUCCEEDED','FAILED') and updated_at<now()-interval '7 days'"); }
}
