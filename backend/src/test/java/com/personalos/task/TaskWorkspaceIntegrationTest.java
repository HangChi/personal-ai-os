package com.personalos.task;

import static org.assertj.core.api.Assertions.*;
import com.personalos.support.ContainerContextTest;
import com.personalos.support.HttpTestClient;
import com.personalos.task.dto.*;
import com.personalos.task.service.*;
import com.personalos.export.*;
import com.personalos.common.json.JsonCodec;
import java.time.Instant;
import java.util.*;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.ai.model.chat=none"})
@Testcontainers
class TaskWorkspaceIntegrationTest extends ContainerContextTest {
    @Container @ServiceConnection static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("pgvector/pgvector:pg17");
    private final TaskService tasks;
    private final WorkspaceService workspace;
    private final JdbcTemplate jdbc;
    private final HttpTestClient http;
    private final DataExportService exporter;
    private final DataImportService importer;
    private final JsonCodec codec;
    @Autowired TaskWorkspaceIntegrationTest(TaskService tasks,WorkspaceService workspace,JdbcTemplate jdbc,
        @Value("${local.server.port}") int port,DataExportService exporter,DataImportService importer,JsonCodec codec){
        this.tasks=tasks;this.workspace=workspace;this.jdbc=jdbc;this.http=new HttpTestClient(port);this.exporter=exporter;this.importer=importer;this.codec=codec;
    }
    @BeforeEach void clean(){jdbc.execute("TRUNCATE project,task,task_tag,chat_session,confirmation,operation_record CASCADE");}
    private ProjectDto project(){return workspace.save(null,new ProjectChange("测试项目",null,null));}
    private TaskDto create(String title,UUID project,UUID parent,String rule,Instant due){return tasks.create(new CreateTaskRequest(title,null,null,due,List.of("工作"),project,parent,rule,"Asia/Shanghai"));}
    @Test void hierarchyAndReadableErrors() throws Exception {
        var p=project();var parent=create("父任务",p.id(),null,"NONE",null);var child=create("子任务",p.id(),parent.id(),"NONE",null);
        var response=http.post("/api/v1/tasks/"+parent.id()+"/complete","{}");
        assertThat(response.status()).isEqualTo(400);assertThat(response.body()).contains("请先完成所有子任务");
        assertThat(http.delete("/api/v1/tasks/"+parent.id(),Map.of()).body()).contains("请先删除或解除所有子任务");
        tasks.setCompleted(child.id(),true);tasks.setCompleted(parent.id(),true);
        assertThat(tasks.get(parent.id()).status()).isEqualTo("DONE");
        assertThatThrownBy(()->create("二级子任务",p.id(),child.id(),"NONE",null)).isInstanceOf(RuntimeException.class);
    }
    @Test void recurrenceUsesGeneratedFieldsAndPreservesTags() {
        var p=project();var first=create("月末复盘",p.id(),null,"MONTHLY",Instant.parse("2026-01-31T01:30:00Z"));
        assertThat(first.recurrenceAnchorDay()).isEqualTo(31);
        var done=tasks.setCompleted(first.id(),true);assertThat(done.recurrenceGenerated()).isTrue();
        tasks.setCompleted(first.id(),true);tasks.setCompleted(first.id(),false);tasks.setCompleted(first.id(),true);
        assertThat(tasks.listAll()).hasSize(2);
        var next=tasks.listAll().stream().filter(t->!t.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(next.dueAt()).isEqualTo(Instant.parse("2026-02-28T01:30:00Z"));assertThat(next.tags()).containsExactly("工作");
        tasks.setCompleted(next.id(),true);
        assertThat(tasks.listAll()).anyMatch(t->Instant.parse("2026-03-31T01:30:00Z").equals(t.dueAt()));
    }
    @Test void backupsPreserveArchivedProjectHierarchyAndGeneration() {
        var p=project();var parent=create("父任务",p.id(),null,"NONE",null);create("子任务",p.id(),parent.id(),"NONE",null);
        var recurring=create("每日任务",p.id(),null,"DAILY",Instant.parse("2026-11-06T01:00:00Z"));tasks.setCompleted(recurring.id(),true);
        workspace.save(p.id(),new ProjectChange(null,null,true));
        var output=new ByteArrayOutputStream();exporter.writeTo(output);
        jdbc.execute("TRUNCATE project,task,task_tag,chat_session,confirmation,operation_record CASCADE");
        importer.importAll(output.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(workspace.projects()).hasSize(1);assertThat(workspace.projects().getFirst().archived()).isTrue();
        assertThat(tasks.get(recurring.id()).recurrenceGenerated()).isTrue();
        tasks.setCompleted(recurring.id(),false);tasks.setCompleted(recurring.id(),true);assertThat(tasks.listAll()).hasSize(4);
    }
    @Test void optionalNewFieldsDoNotChangeLegacyIdempotencyDigest(){
        var request=new UpdateTaskRequest("renamed",null,null,null,null,null,null);
        assertThat(codec.digestOf(request)).isEqualTo("03d482d74dad6316524418e1be57e570be64fa687e8bf729ce85c4eca8f1adca");
    }
}
