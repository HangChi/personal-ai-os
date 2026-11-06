package com.personalos.knowledge.capture;

import com.personalos.common.json.JsonCodec;
import com.personalos.common.exception.*;
import com.personalos.common.api.ErrorCode;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KnowledgeCaptureService {
    private final PublicWebFetcher fetcher;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public KnowledgeCaptureService(PublicWebFetcher fetcher,JdbcTemplate jdbc,PlatformTransactionManager manager) {this.fetcher=fetcher;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    public record Capture(UUID id,String url,UUID documentId,int currentVersion,UUID pendingJobId,String status,String lastError,String lastCheckedAt) {}
    public record Version(int version,String title,String content,String hash,String status,String capturedAt) {}
    private static final String COLUMNS="SELECT c.id,c.canonical_url,c.document_id,c.current_version,c.pending_job_id,coalesce(j.status,CASE WHEN c.last_error IS NOT NULL THEN 'FAILED' WHEN c.document_id IS NULL THEN 'EMPTY' ELSE 'SUCCEEDED' END) status,coalesce(c.last_error,j.error) error,c.last_checked_at::text FROM knowledge_capture c LEFT JOIN knowledge_index_job j ON j.id=c.pending_job_id";
    private Capture map(java.sql.ResultSet rs) throws java.sql.SQLException { return new Capture(rs.getObject(1,UUID.class),rs.getString(2),rs.getObject(3,UUID.class),rs.getInt(4),rs.getObject(5,UUID.class),rs.getString(6),rs.getString(7),rs.getString(8)); }
    public Map<String,Object> list() {return Map.of("items",jdbc.query(COLUMNS+" ORDER BY c.created_at DESC LIMIT 100",(rs,n)->map(rs)),"total",jdbc.queryForObject("SELECT count(*) FROM knowledge_capture",Long.class));}
    public Capture get(UUID id) {return jdbc.query(COLUMNS+" WHERE c.id=?",(rs,n)->map(rs),id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("采集来源不存在"));}
    public Capture capture(String url) {
        var page=fetcher.fetch(url); // DNS、HTTP 和解析均在短写事务之外。
        try {
            UUID id=tx.execute(status->jdbc.queryForObject("SELECT queue_knowledge_capture(?,?,?,?)",UUID.class,page.url(),page.title(),page.content(),JsonCodec.sha256Hex(page.content())));
            return get(id);
        } catch(org.springframework.dao.DataAccessException ex) {throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,"采集提交失败，可能队列已满；请稍后重试");}
    }
    public Capture refresh(UUID id) {
        var current=get(id);if(current.pendingJobId()!=null)return current;
        try {return capture(current.url());}
        catch(BusinessException ex) {jdbc.update("UPDATE knowledge_capture SET last_checked_at=now(),last_error=? WHERE id=?",ex.getMessage(),id);throw ex;}
    }
    public Map<String,Object> versions(UUID id) {
        get(id);
        var items=jdbc.query("SELECT version,title,content,content_hash,status,captured_at::text FROM knowledge_capture_version WHERE capture_id=? ORDER BY version DESC LIMIT 10",(rs,n)->new Version(rs.getInt(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6)),id);
        return Map.of("items",items,"total",jdbc.queryForObject("SELECT count(*) FROM knowledge_capture_version WHERE capture_id=?",Long.class,id));
    }
}
