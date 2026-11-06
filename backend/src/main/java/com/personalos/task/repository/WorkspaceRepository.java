package com.personalos.task.repository;

import com.personalos.task.dto.ProjectDto;
import com.personalos.task.dto.ProjectChange;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 项目与最近执行的有界 SQL 读取。 */
@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbc;
    public WorkspaceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final RowMapper<ProjectDto> PROJECT=(r,n)->new ProjectDto(r.getObject("id",UUID.class),r.getString("name"),
        r.getString("description"),r.getBoolean("archived"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    public List<ProjectDto> projects(){return jdbc.query("SELECT * FROM project ORDER BY created_at,id",PROJECT);}
    public List<ProjectDto> exportBatch(int page,int size){return jdbc.query("SELECT * FROM project ORDER BY id LIMIT ? OFFSET ?",PROJECT,size,(long)page*size);}
    public ProjectDto save(UUID id,ProjectChange c){
        if(id==null) return jdbc.queryForObject("INSERT INTO project(name,description) VALUES(?,?) RETURNING *",PROJECT,c.name(),c.description());
        var rows=jdbc.query("UPDATE project SET name=coalesce(?,name),description=coalesce(?,description),archived=coalesce(?,archived),updated_at=now() WHERE id=? RETURNING *",PROJECT,c.name(),c.description(),c.archived(),id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public record RunSummary(String id,String conversationId,String title,String status,Instant updatedAt){}
    public record RunPage(List<RunSummary> items,long total){}
    private static final String LATEST="""
        FROM (SELECT DISTINCT ON (r.conversation_id) r.id,r.conversation_id,s.title,r.status,r.updated_at
          FROM chat_run r JOIN chat_session s USING(conversation_id)
          ORDER BY r.conversation_id,r.created_at DESC,r.id DESC) latest WHERE status<>'COMPLETED'
        """;
    public RunPage unfinishedRuns(){
        Long total=jdbc.queryForObject("SELECT count(*) "+LATEST,Long.class);
        var items=jdbc.query("SELECT id,conversation_id,coalesce(title,'未命名会话') AS title,status,updated_at "+LATEST+" ORDER BY updated_at DESC,id DESC LIMIT 20",
            (r,n)->new RunSummary(r.getString("id"),r.getString("conversation_id"),r.getString("title"),r.getString("status"),r.getTimestamp("updated_at").toInstant()));
        return new RunPage(items,total==null?0:total);
    }
}
