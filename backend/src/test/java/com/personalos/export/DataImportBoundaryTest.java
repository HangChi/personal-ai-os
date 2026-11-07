package com.personalos.export;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.knowledge.governance.KnowledgeGovernance;
import com.personalos.knowledge.service.VectorStoreInspector;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class DataImportBoundaryTest {
    static final String ID="10000000-0000-4000-8000-000000000001", OTHER="10000000-0000-4000-8000-000000000002", AT="2026-11-07T08:00:00Z";
    record Write(String sql, Map<Integer,Object> args) {}
    static Map<String,Object> row(Object... xs) { var m=new LinkedHashMap<String,Object>(); for(int i=0;i<xs.length;i+=2)m.put((String)xs[i],xs[i+1]);return m; }
    class Fixture {
        final EntityManager em=mock(EntityManager.class);
        final EmbeddingModel embedding=mock(EmbeddingModel.class);
        final VectorStoreInspector inspector=mock(VectorStoreInspector.class);
        final KnowledgeGovernance governance=mock(KnowledgeGovernance.class);
        final PlatformTransactionManager tm=mock(PlatformTransactionManager.class);
        final TransactionStatus status=mock(TransactionStatus.class);
        final JsonCodec codec=new JsonCodec(JsonMapper.builder().build());
        final List<Write> writes=new ArrayList<>();
        final AtomicInteger reads=new AtomicInteger();
        Object count=0L; int occupyAfter=Integer.MAX_VALUE;
        final DataImportService service;
        Fixture() {
            when(tm.getTransaction(any())).thenReturn(status);
            when(em.createNativeQuery(anyString())).thenAnswer(inv->{
                String sql=inv.getArgument(0); var args=new HashMap<Integer,Object>();
                Query q=mock(Query.class, RETURNS_SELF);
                when(q.setParameter(anyInt(),nullable(Object.class))).thenAnswer(a->{args.put(a.getArgument(0),a.getArgument(1));return q;});
                when(q.getSingleResult()).thenAnswer(a->reads.incrementAndGet()>occupyAfter?1L:count);
                when(q.executeUpdate()).thenAnswer(a->{writes.add(new Write(sql,new HashMap<>(args)));return 1;});
                return q;
            });
            service=new DataImportService(em,embedding,inspector,codec,tm,governance);
        }
        DataImportService.ImportResult run(Map<String,Object> payload) {return service.importAll(codec.toJson(payload));}
        List<Write> rows(String table){return writes.stream().filter(w->w.sql().startsWith("insert into "+table+" ")||w.sql().startsWith("insert into "+table+"(")).toList();}
    }
    @Test void validatesFormatAndEmptyDatabaseBeforeOpeningWriteTransaction() {
        for(Object version:Arrays.asList(null,"","2")) { var f=new Fixture(); assertThatThrownBy(()->f.run(row("formatVersion",version))).hasMessageContaining("格式版本");verifyNoInteractions(f.tm,f.embedding); }
        var broken=new Fixture(); assertThatThrownBy(()->broken.service.importAll("{broken")).hasMessageContaining("合法的导出 JSON");verifyNoInteractions(broken.tm);
        var occupied=new Fixture();occupied.count=2L;assertThatThrownBy(()->occupied.run(row("formatVersion","1"))).hasMessageContaining("project=2, knowledge_index_job=2");verifyNoInteractions(occupied.tm);
        for(Object count:Arrays.asList(null,"0",0L)) {var f=new Fixture();f.count=count;var r=f.run(row("formatVersion"," 1 "));assertThat(r.sessions()).isZero();assertThat(r.notes()).isEmpty();verify(f.tm).commit(f.status);assertThat(f.writes).hasSize(1);}
        var concurrent=new Fixture();concurrent.occupyAfter=11;assertThatThrownBy(()->concurrent.run(row("formatVersion","1"))).hasMessageContaining("目标库已有数据");verify(concurrent.tm).rollback(concurrent.status);assertThat(concurrent.writes).hasSize(1);
    }
    @Test void restoresSessionsTasksProjectsCalendarAndMemoryWithOriginalIdentityAndDefaults() {
        var f=new Fixture();
        var payload=row("formatVersion","1",
            "projects",List.of(row("id",ID,"name"," 项目 ","archived",true),row("id",OTHER,"name","Other","archived",false,"createdAt",AT,"updatedAt",AT)),
            "sessions",List.of(row("session",null),row("session",row("conversationId"," ")),row("session",row("conversationId","s","title"," ","readOnlyReport",true),"messages",List.of(row("role",null),row("role","system","content","reject"),row("role","user","content",null),row("role","user","content","a","sequenceNo",0),row("role","assistant","content","b","sequenceNo",8,"createdAt",AT),row("role","USER","content","c"))),row("session",row("conversationId","t","title","title","createdAt",AT,"updatedAt",AT),"messages",List.of())),
            "tasks",List.of(row("id",null,"title","skip"),row("id",ID,"title"," "),row("id",ID,"title","task","status","done","priority","high","previousStatus","doing","parentTaskId",OTHER,"recurrenceType","DAILY","recurrenceTimezone","UTC","recurrenceGenerated",true,"createdAt",AT,"updatedAt",AT,"tags",Arrays.asList(null," ","tag")),row("id",OTHER,"title","fallback","status","bad","priority","bad","tags",List.of())),
            "calendarEvents",List.of(row("id",null),row("id",ID,"title"," "),row("id",ID,"title","skip"),row("id",ID,"title","event","startsAt",AT,"allDay",true),row("id",OTHER,"title","second","startsAt",AT,"endsAt",AT,"createdAt",AT,"recurrenceType","DAILY","reminderLeadMinutes",30)),
            "memories",List.of(row("id",null),row("id",ID,"content"," "),row("id",ID,"content","remember","supersededBy",OTHER),row("id",OTHER,"content","current","source","AUTO","pinned",true,"hitCount",4,"createdAt",AT,"updatedAt",AT,"lastUsedAt",AT)));
        var r=f.run(payload);assertThat(r.messages()).isEqualTo(3);assertThat(r.tasks()).isEqualTo(2);assertThat(r.calendarEvents()).isEqualTo(2);assertThat(r.memories()).isEqualTo(2);
        assertThat(f.rows("project").getFirst().args().get(2)).isEqualTo("项目");
        assertThat(f.rows("chat_session").getFirst().args().get(2)).isEqualTo("导入的会话");
        assertThat(f.rows("chat_message").stream().map(w->w.args().get(4)).toList()).containsExactly(1,8,9);
        assertThat(f.rows("task").getFirst().args()).containsEntry(4,"DONE").containsEntry(5,"HIGH").containsEntry(10,"DOING").containsEntry(15,true);
        assertThat(f.rows("task").getLast().args()).containsEntry(4,"TODO").containsEntry(5,"MEDIUM").containsEntry(10,null).containsEntry(12,"NONE");
        assertThat(f.rows("task_tag")).hasSize(1);assertThat(f.rows("task_tag").getFirst().args().get(2)).isEqualTo(0);
        assertThat(f.rows("calendar_event").getFirst().args()).containsEntry(8,"NONE").containsEntry(10,15);
        assertThat(f.rows("memory").getFirst().args()).containsEntry(3,JsonCodec.sha256Hex("remember")).containsEntry(6,"MANUAL").containsEntry(8,0);
        assertThat(f.writes.stream().filter(w->w.sql().contains("superseded_by"))).hasSize(1);
        verify(f.tm).commit(f.status);
    }
    @Test void invalidProjectsRollbackAndMissingSessionMessagesAreAllowed() {
        for(Object project:Arrays.asList(null,row("name","p"),row("id",ID),row("id",ID,"name"," "))) {var f=new Fixture();assertThatThrownBy(()->f.run(row("formatVersion","1","projects",Arrays.asList(project)))).hasMessageContaining("备份项目");verify(f.tm).rollback(f.status);}
        var f=new Fixture();var r=f.run(row("formatVersion","1","sessions",List.of(row("session",row("conversationId","s")))));assertThat(r.messages()).isZero();verify(f.tm).commit(f.status);
    }
    @Test void documentPreparationSkipsInvalidEntriesAndNeverWritesPartialEmbeddings() {
        var f=new Fixture();
        var r=f.run(row("formatVersion","1","knowledgeDocuments",List.of(row("document",null),row("document",row()),row("document",row("id",ID)),row("document",row("id",ID),"chunks",List.of()),row("document",row("id",ID),"chunks",Arrays.asList(null," ")))));
        assertThat(r.documents()).isEqualTo(1);assertThat(r.vectors()).isZero();assertThat(r.notes()).hasSize(1);assertThat(f.rows("knowledge_document").getFirst().args()).containsEntry(3,"imported.md").containsEntry(4,"text/markdown").containsEntry(5,0L).containsEntry(6,0);verifyNoInteractions(f.embedding,f.inspector);
        var mismatch=new Fixture();when(mismatch.embedding.embed(anyList(),any(),any())).thenReturn(List.of());
        assertThatThrownBy(()->mismatch.run(row("formatVersion","1","knowledgeDocuments",List.of(row("document",row("id",ID,"title","Title","filename","a.md"),"chunks",List.of("body")))))).hasMessageContaining("向量数量");verifyNoInteractions(mismatch.tm,mismatch.inspector);assertThat(mismatch.writes).isEmpty();
    }
}
