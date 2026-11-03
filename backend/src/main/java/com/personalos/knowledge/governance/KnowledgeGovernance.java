package com.personalos.knowledge.governance;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.rag.RagResult;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeGovernance {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final com.personalos.knowledge.service.VectorStoreInspector vectors;
    public KnowledgeGovernance(JdbcTemplate db,ObjectMapper json,com.personalos.knowledge.service.VectorStoreInspector vectors){this.db=db;this.json=json;this.vectors=vectors;}
    public record Space(UUID id,String name) { }
    public record Metadata(UUID spaceId,String version,String scope,LocalDate effectiveFrom,LocalDate effectiveUntil,String authority,String lifecycle) { }
    public record DocumentSource(UUID documentId,String title,SourceInfo source) { }
    public record Conflict(UUID id,UUID leftId,UUID rightId,int leftChunk,int rightChunk,String leftQuote,String rightQuote,
                           String reason,String status,String resolution,UUID winnerId,String resolutionReason) { }
    public List<Space> spaces(){return db.query("SELECT id,name FROM knowledge_space ORDER BY name",(r,n)->new Space(r.getObject(1,UUID.class),r.getString(2)));}
    public Space createSpace(String name) {
        if(name==null || name.isBlank() || name.strip().length()>120) throw invalid("知识库名称需为 1 至 120 字符");
        UUID id=UUID.randomUUID();
        try { db.update("INSERT INTO knowledge_space VALUES(?,?)",id,name.strip()); }
        catch(org.springframework.dao.DuplicateKeyException ex){throw invalid("知识库名称已存在");}
        return new Space(id,name.strip());
    }
    private final String select="SELECT s.*,k.name AS space_name,d.title FROM knowledge_source s JOIN knowledge_space k ON k.id=s.space_id JOIN knowledge_document d ON d.id=s.document_id";
    private SourceInfo info(java.sql.ResultSet r) throws java.sql.SQLException {
        return new SourceInfo(r.getObject("space_id",UUID.class),r.getString("space_name"),r.getString("version"),r.getString("scope"),
            r.getObject("effective_from",LocalDate.class),r.getObject("effective_until",LocalDate.class),r.getString("authority"),r.getString("lifecycle"),r.getLong("revision"),r.getString("review_state"));
    }
    public List<DocumentSource> sources(){return db.query(select+" ORDER BY d.updated_at DESC LIMIT 500",(r,n)->new DocumentSource(r.getObject("document_id",UUID.class),r.getString("title"),info(r)));}
    public Map<UUID,SourceInfo> sources(Collection<UUID> ids) {
        if(ids.isEmpty()) return Map.of();
        var result=new HashMap<UUID,SourceInfo>();
        String parameters=String.join(",",Collections.nCopies(ids.size(),"?"));
        db.query(select+" WHERE s.document_id IN ("+parameters+")",(org.springframework.jdbc.core.RowCallbackHandler)r->result.put(r.getObject("document_id",UUID.class),info(r)),ids.toArray());
        return result;
    }
    public List<KnowledgeHitDto> enrich(List<KnowledgeHitDto> hits) {
        var metadata=sources(hits.stream().map(KnowledgeHitDto::documentId).filter(Objects::nonNull).distinct().toList());
        return hits.stream().filter(h->metadata.containsKey(h.documentId()))
            .map(h->h.withSource(metadata.get(h.documentId())))
            .filter(h->!h.source().lifecycle().equals("SUPERSEDED")).toList();
    }
    @Transactional public SourceInfo update(UUID id,Metadata m) {
        if(m==null || m.spaceId()==null || !Set.of("ACTIVE","SUPERSEDED").contains(m.lifecycle()==null?"":m.lifecycle())) throw invalid("请选择知识库与文档状态");
        if(m.effectiveFrom()!=null && m.effectiveUntil()!=null && m.effectiveUntil().isBefore(m.effectiveFrom()))throw invalid("有效截止日期早于生效日期");
        if(db.queryForObject("SELECT count(*) FROM knowledge_space WHERE id=?",Integer.class,m.spaceId())==0)throw invalid("知识库不存在");
        int updated=db.update("UPDATE knowledge_source SET space_id=?,version=?,scope=?,effective_from=?,effective_until=?,authority=?,lifecycle=? WHERE document_id=?",
            m.spaceId(),text(m.version(),120),text(m.scope(),500),m.effectiveFrom(),m.effectiveUntil(),text(m.authority(),300),m.lifecycle(),id);
        if(updated==0)throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"文档不存在");
        return sources(List.of(id)).get(id);
    }
    public List<Conflict> conflicts(){return db.query("SELECT * FROM knowledge_conflict ORDER BY created_at DESC LIMIT 200",(r,n)->new Conflict(r.getObject("id",UUID.class),r.getObject("left_id",UUID.class),r.getObject("right_id",UUID.class),r.getInt("left_chunk"),r.getInt("right_chunk"),r.getString("left_quote"),r.getString("right_quote"),r.getString("reason"),r.getString("status"),r.getString("resolution"),r.getObject("winner_id",UUID.class),r.getString("resolution_reason")));}
    public record Resolution(String decision,UUID winnerId,String reason) { }
    @Transactional public void resolve(UUID id,Resolution resolution) {
        if(resolution==null || !Set.of("PREFER_SOURCE","KEEP_BOTH","NOT_CONFLICT").contains(resolution.decision()==null?"":resolution.decision()))throw invalid("处理方式无效");
        String reason=text(resolution.reason(),1000);if(reason.isBlank())throw invalid("请填写处理依据");
        var rows=db.queryForList("SELECT left_id,right_id,status FROM knowledge_conflict WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,"冲突不存在");
        var row=rows.getFirst();if(!"OPEN".equals(row.get("status")))throw invalid("资料已变化或冲突已处理，请刷新后复核");
        if(resolution.decision().equals("PREFER_SOURCE") && !Objects.equals(row.get("left_id"),resolution.winnerId())&&!Objects.equals(row.get("right_id"),resolution.winnerId()))throw invalid("选定来源必须为冲突双方之一");
        UUID winner=resolution.decision().equals("PREFER_SOURCE")?resolution.winnerId():null;
        db.update("UPDATE knowledge_conflict SET status='RESOLVED',resolution=?,winner_id=?,resolution_reason=?,resolved_at=now() WHERE id=?",resolution.decision(),winner,reason,id);
        db.update("INSERT INTO knowledge_conflict_audit(conflict_id,decision) VALUES(?,?::jsonb)",id,json.writeValueAsString(resolution));
    }
    /** Store only server-validated evidence, fenced by source revision; model never resolves a conflict. */
    @Transactional public void record(List<RagResult.Disagreement> conflicts) {
        for(var c:conflicts) {
            if(c.left().source()==null || c.right().source()==null || c.left().documentId().equals(c.right().documentId()))continue;
            var a=c.left();var b=c.right();String leftQuote=c.leftQuote(),rightQuote=c.rightQuote();
            if(a.documentId().compareTo(b.documentId())>0){var swap=a;a=b;b=swap;String quote=leftQuote;leftQuote=rightQuote;rightQuote=quote;}
            db.queryForList("SELECT document_id FROM knowledge_source WHERE document_id IN (?,?) ORDER BY document_id FOR SHARE",a.documentId(),b.documentId());
            var current=sources(List.of(a.documentId(),b.documentId()));
            if(current.size()!=2 || current.get(a.documentId()).revision()!=a.source().revision() || current.get(b.documentId()).revision()!=b.source().revision())continue;
            String fingerprint=JsonCodec.sha256Hex(a.documentId()+":"+a.source().revision()+":"+b.documentId()+":"+b.source().revision()+":"+leftQuote+":"+rightQuote);
            db.update("INSERT INTO knowledge_conflict(fingerprint,left_id,right_id,left_revision,right_revision,left_chunk,right_chunk,left_quote,right_quote,reason) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(fingerprint) DO NOTHING",
                fingerprint,a.documentId(),b.documentId(),a.source().revision(),b.source().revision(),a.chunkIndex(),b.chunkIndex(),leftQuote,rightQuote,c.reason());
        }
    }
    /** Include the known opposing evidence even when it ranked outside topK. */
    public List<KnowledgeHitDto> expandConflictingSources(List<KnowledgeHitDto> pool) {
        var expanded=new ArrayList<>(pool);
        var ids=pool.stream().map(KnowledgeHitDto::documentId).collect(java.util.stream.Collectors.toSet());
        if(ids.isEmpty())return expanded;
        String p=String.join(",",Collections.nCopies(ids.size(),"?"));var args=new ArrayList<Object>(ids);args.addAll(ids);
        for(var row:db.queryForList("SELECT left_id,right_id,left_chunk,right_chunk FROM knowledge_conflict WHERE status IN ('OPEN','RESOLVED') AND (left_id IN ("+p+") OR right_id IN ("+p+")) ORDER BY created_at DESC LIMIT 12",args.toArray())) {
            for(String side:List.of("left","right")) {
                UUID id=(UUID)row.get(side+"_id");int chunk=((Number)row.get(side+"_chunk")).intValue();
                if(expanded.stream().anyMatch(h->h.documentId().equals(id)&&h.chunkIndex()==chunk))continue;
                var raw=vectors.documentChunk(id,chunk);if(raw.isEmpty())continue;
                var meta=json.readTree(raw.getFirst().metadata());
                expanded.addAll(enrich(List.of(new KnowledgeHitDto(id,meta.path("document_title").asString(""),meta.path("filename").asString(""),chunk,raw.getFirst().content(),null))));
            }
        }
        return expanded;
    }

    /** Human decisions are scoped to the reviewed evidence and its exact source revisions. */
    public com.personalos.knowledge.rag.EvidenceReview.Review applyDecisions(com.personalos.knowledge.rag.EvidenceReview.Review review,List<KnowledgeHitDto> pool,List<String> warnings) {
        var current=new ArrayList<>(review.conflicts());
        var byDocument=new HashMap<UUID,List<KnowledgeHitDto>>();
        pool.forEach(h->byDocument.computeIfAbsent(h.documentId(),key->new ArrayList<>()).add(h));
        // Persisted open findings cannot disappear just because the latest model misses them.
        for(var row:db.queryForList("SELECT * FROM knowledge_conflict WHERE status IN ('OPEN','RESOLVED') ORDER BY created_at DESC LIMIT 1000")) {
            UUID l=(UUID)row.get("left_id"),r=(UUID)row.get("right_id");
            if(!byDocument.containsKey(l)||!byDocument.containsKey(r))continue;
            for(var a:byDocument.get(l))for(var b:byDocument.get(r)) {
                if(a.source()==null||b.source()==null||a.source().revision()!=((Number)row.get("left_revision")).longValue()
                    ||b.source().revision()!=((Number)row.get("right_revision")).longValue()
                    ||!a.content().contains((String)row.get("left_quote"))||!b.content().contains((String)row.get("right_quote")))continue;
                if(current.stream().noneMatch(c->sameEvidence(c,a,b,(String)row.get("left_quote"),(String)row.get("right_quote"))))current.add(new RagResult.Disagreement(a,b,(String)row.get("left_quote"),(String)row.get("right_quote"),(String)row.get("reason")));
            }
        }
        var unresolved=new ArrayList<RagResult.Disagreement>();var relevant=new LinkedHashSet<>(review.relevant());
        for(var c:current) {
            if(c.left().source()==null || c.right().source()==null){unresolved.add(c);continue;}
            var decisions=db.queryForList("SELECT resolution,winner_id,resolution_reason FROM knowledge_conflict WHERE status='RESOLVED' AND ((left_id=? AND right_id=? AND left_revision=? AND right_revision=? AND left_quote=? AND right_quote=?) OR (left_id=? AND right_id=? AND left_revision=? AND right_revision=? AND left_quote=? AND right_quote=?))",
                c.left().documentId(),c.right().documentId(),c.left().source().revision(),c.right().source().revision(),c.leftQuote(),c.rightQuote(),c.right().documentId(),c.left().documentId(),c.right().source().revision(),c.left().source().revision(),c.rightQuote(),c.leftQuote());
            var decision=decisions.stream().filter(d->!"KEEP_BOTH".equals(d.get("resolution"))).findFirst();
            if(decision.isPresent()) {
                var d=decision.get();warnings.add("Human review: "+d.get("resolution_reason"));
                if("PREFER_SOURCE".equals(d.get("resolution")))relevant.remove(Objects.equals(c.left().documentId(),d.get("winner_id"))?c.right():c.left());
            } else {unresolved.add(c);relevant.add(c.left());relevant.add(c.right());}
        }
        return new com.personalos.knowledge.rag.EvidenceReview.Review(List.copyOf(relevant),List.copyOf(unresolved),review.clarification());
    }
    private static boolean sameEvidence(RagResult.Disagreement c,KnowledgeHitDto a,KnowledgeHitDto b,String left,String right) {
        return c.left().documentId().equals(a.documentId())&&c.right().documentId().equals(b.documentId())&&c.leftQuote().equals(left)&&c.rightQuote().equals(right)
            ||c.left().documentId().equals(b.documentId())&&c.right().documentId().equals(a.documentId())&&c.leftQuote().equals(right)&&c.rightQuote().equals(left);
    }

    @Transactional public void retry(UUID id) {
        db.update("UPDATE knowledge_source SET review_state='PENDING' WHERE document_id=?",id);
        db.update("INSERT INTO knowledge_review_job(document_id,revision) SELECT document_id,revision FROM knowledge_source WHERE document_id=? ON CONFLICT(document_id) DO UPDATE SET chunk_cursor=0,next_attempt_at=now(),lease_token=NULL,attempts=0,last_error=NULL",id);
    }
    private static String text(String s,int max){if(s==null)return "";if(s.length()>max)throw invalid("字段超过长度限制");return s.strip();}
    private static BusinessException invalid(String message){return new BusinessException(ErrorCode.VALIDATION_FAILED,message);}
}
