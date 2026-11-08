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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "DAILY,2026-03-07T14:00:00Z,2026-03-08T13:00:00Z",
        "DAILY,2026-10-31T13:00:00Z,2026-11-01T14:00:00Z",
        "WEEKLY,2026-03-01T14:00:00Z,2026-03-08T13:00:00Z",
        "WEEKLY,2026-10-25T13:00:00Z,2026-11-01T14:00:00Z",
        "WEEKDAYS,2026-03-06T14:00:00Z,2026-03-09T13:00:00Z",
        "MONTHLY,2028-01-31T14:00:00Z,2028-02-29T14:00:00Z"
    })
    void recurrenceUsesLocalTimeAcrossDstWeekendsAndLeapDay(String rule,String due,String nextDue) {
        var project=project();
        var first=tasks.create(new CreateTaskRequest("周期复盘","保留说明",com.personalos.task.entity.TaskPriority.HIGH,
            Instant.parse(due),List.of("工作","复盘"),project.id(),null,rule,"America/New_York"));
        tasks.setCompleted(first.id(),true);
        assertThat(tasks.listAll()).hasSize(2);
        var next=tasks.listAll().stream().filter(task->!task.id().equals(first.id())).findFirst().orElseThrow();
        assertThat(next.dueAt()).isEqualTo(Instant.parse(nextDue));
        assertThat(next.dueAt().atZone(java.time.ZoneId.of("America/New_York")).getHour()).isEqualTo(9);
        assertThat(next.title()).isEqualTo(first.title());
        assertThat(next.description()).isEqualTo(first.description());
        assertThat(next.priority()).isEqualTo(first.priority());
        assertThat(next.tags()).containsExactlyElementsOf(first.tags());
        assertThat(next.projectId()).isEqualTo(project.id());
        assertThat(next.recurrenceType()).isEqualTo(rule);
        assertThat(next.recurrenceTimezone()).isEqualTo("America/New_York");
        assertThat(next.status()).isEqualTo("TODO");
        assertThat(next.completedAt()).isNull();
        assertThat(next.recurrenceGenerated()).isFalse();
        assertThat(tasks.get(first.id()).recurrenceGenerated()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"cross-project","self-parent","move-parent","complete-parent"})
    void rejectedHierarchyChangesRollbackAllFields(String operation) {
        var project=project();var other=project();
        var parent=create("父任务",project.id(),null,"NONE",null);
        var child=create("子任务",project.id(),parent.id(),"NONE",null);
        boolean targetParent=operation.equals("move-parent")||operation.equals("complete-parent");
        var before=tasks.get(targetParent?parent.id():child.id());
        var request=new UpdateTaskRequest("不应保存的标题","不应保存的描述",com.personalos.task.entity.TaskPriority.HIGH,null,null,
            operation.equals("complete-parent")?com.personalos.task.entity.TaskStatus.DONE:null,List.of("不应保存"),
            operation.equals("cross-project")||operation.equals("move-parent")?other.id():null,
            operation.equals("self-parent")?child.id():null,null,null,null,null);
        assertThatThrownBy(()->tasks.update(before.id(),request)).isInstanceOf(RuntimeException.class);
        assertThat(tasks.get(before.id())).isEqualTo(before);
        assertThat(tasks.get(child.id()).parentTaskId()).isEqualTo(parent.id());
        assertThat(tasks.listAll()).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void recurringDueCannotBeClearedThroughEitherWritePath(boolean patch) {
        var task=create("每日任务",null,null,"DAILY",Instant.parse("2026-11-08T01:00:00Z"));
        var before=tasks.get(task.id());
        assertThatThrownBy(()->{
            if(patch) tasks.update(task.id(),new UpdateTaskRequest("不应保存",null,null,null,true,null,null));
            else tasks.reschedule(task.id(),null);
        }).isInstanceOf(RuntimeException.class);
        assertThat(tasks.get(task.id())).isEqualTo(before);
        assertThat(tasks.listAll()).hasSize(1);
    }

    @Test void workspaceDueWindowIsHalfOpenAndFiltersCompletedTasksAndOtherProjects() {
        var project=project();var other=project();
        var from=Instant.parse("2026-11-08T00:00:00Z");var until=from.plusSeconds(86400);
        var included=create("左端点",project.id(),null,"NONE",from);
        create("右端点",project.id(),null,"NONE",until);
        create("无截止时间",project.id(),null,"NONE",null);
        create("其他项目",other.id(),null,"NONE",from.plusSeconds(1));
        var done=create("已完成",project.id(),null,"NONE",from.plusSeconds(1));tasks.setCompleted(done.id(),true);
        var result=tasks.search(new TaskQuery(null,null,"工作",0,20,project.id(),null,true,from,until));
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).extracting(TaskDto::id).containsExactly(included.id());
    }
}
