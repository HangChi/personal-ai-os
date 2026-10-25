package com.personalos.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.support.HttpTestClient;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskQuery;
import com.personalos.task.dto.UpdateTaskRequest;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.repository.TaskRepository;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 任务 REST 与两层幂等的集成测试。
 *
 * <p>注意这里**没有**提供任何 ChatModel：任务接口不依赖 AI 能力，
 * 因此这组测试同时证明了「未配置模型时任务功能照常可用」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none"})
@Testcontainers
class TaskApiIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private final HttpTestClient http;
    private final TaskService taskService;
    private final TaskRepository taskRepository;

    @Autowired
    TaskApiIntegrationTest(@Value("${local.server.port}") int port,
                           TaskService taskService,
                           TaskRepository taskRepository) {
        this.http = new HttpTestClient(port);
        this.taskService = taskService;
        this.taskRepository = taskRepository;
    }

    @BeforeEach
    void cleanUp() {
        taskRepository.deleteAll();
    }

    // ---------------------------------------------------------------- 基本 CRUD

    @Test
    @DisplayName("创建任务：201，且数据库真实新增 1 行")
    void createTaskInsertsRow() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/tasks",
                "{\"title\":\"交周报\",\"priority\":\"HIGH\"}");

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body()).contains("\"title\":\"交周报\"").doesNotContain("\"success\"");
        assertThat(taskRepository.findAll()).hasSize(1);
        assertThat(taskRepository.findAll().get(0).getPriority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    @DisplayName("创建任务：标题为空 → 400，零写入")
    void blankTitleIsRejected() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/tasks", "{\"title\":\"   \"}");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("查询列表：支持状态筛选与关键词匹配")
    void listSupportsFiltering() throws Exception {
        taskService.create(new CreateTaskRequest("写周报", "周报正文", null, null, null));
        taskService.create(new CreateTaskRequest("买牛奶", null, null, null, null));

        assertThat(http.get("/api/v1/tasks?keyword=周报").body()).contains("写周报").doesNotContain("买牛奶");
        assertThat(http.get("/api/v1/tasks?status=TODO").body()).contains("写周报").contains("买牛奶");
        assertThat(http.get("/api/v1/tasks?status=DONE").body()).contains("\"items\":[]");
    }

    @Test
    @DisplayName("查询列表：非法状态枚举 → 400 而非 500")
    void invalidEnumQueryParamIsClientError() throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/tasks?status=NOT_A_STATUS");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":40000");
    }

    @Test
    @DisplayName("单个任务：不存在 → 404/40004")
    void unknownTaskReturns404() throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/tasks/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":40004");
    }

    @Test
    @DisplayName("修改任务：只改出现的字段；clearDueAt 才清除截止时间")
    void patchOnlyUpdatesPresentFields() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest(
                "原名", "原描述", TaskPriority.LOW, Instant.parse("2026-10-20T10:00:00Z"), null)).id();

        // 只改标题：截止时间与优先级必须原样保留
        HttpTestClient.Response renamed = http.patch("/api/v1/tasks/" + id,
                "{\"title\":\"新名\"}", Map.of());
        assertThat(renamed.status()).isEqualTo(200);

        var afterRename = taskRepository.findById(id).orElseThrow();
        assertThat(afterRename.getTitle()).isEqualTo("新名");
        assertThat(afterRename.getPriority()).isEqualTo(TaskPriority.LOW);
        assertThat(afterRename.getDueAt()).isNotNull();

        // 显式清除截止时间
        http.patch("/api/v1/tasks/" + id, "{\"clearDueAt\":true}", Map.of());
        assertThat(taskRepository.findById(id).orElseThrow().getDueAt()).isNull();
    }

    @Test
    @DisplayName("完成与取消完成：维护 completedAt 不变量")
    void completeAndReopenMaintainInvariant() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("要做的事", null, null, null, null)).id();

        assertThat(http.post("/api/v1/tasks/" + id + "/complete", "").status()).isEqualTo(200);
        var done = taskRepository.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(done.getCompletedAt()).isNotNull();

        assertThat(http.post("/api/v1/tasks/" + id + "/reopen", "").status()).isEqualTo(200);
        var reopened = taskRepository.findById(id).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(reopened.getCompletedAt()).as("取消完成后不应残留完成时间").isNull();
    }

    @Test
    @DisplayName("删除任务：人工直连立即生效，无需确认；重复删除 → 404")
    void deleteIsImmediateForHumans() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("要删的任务", null, null, null, null)).id();

        HttpTestClient.Response deleted = http.delete("/api/v1/tasks/" + id, Map.of());

        assertThat(deleted.status()).isEqualTo(200);
        assertThat(taskRepository.existsById(id)).isFalse();
        assertThat(http.delete("/api/v1/tasks/" + id, Map.of()).status()).isEqualTo(404);
    }

    // ---------------------------------------------------------------- HTTP 层幂等

    @Test
    @DisplayName("同一 Idempotency-Key 重复创建：只写 1 行，且返回相同 data")
    void sameIdempotencyKeyCreatesOnce() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", "it-test-key-0001");
        String body = "{\"title\":\"幂等任务\"}";

        HttpTestClient.Response first = http.post("/api/v1/tasks", body, headers);
        HttpTestClient.Response second = http.post("/api/v1/tasks", body, headers);

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(taskRepository.findAll()).hasSize(1);
        // data 必须一致（外层信封的 timestamp 是本次响应生成的，不属于幂等语义）
        assertThat(extractData(second.body())).isEqualTo(extractData(first.body()));
    }

    @Test
    @DisplayName("同一幂等键配不同内容 → 409/40001，且不产生额外写入")
    void sameKeyWithDifferentBodyConflicts() throws Exception {
        Map<String, String> headers = Map.of("Idempotency-Key", "it-test-key-0002");

        http.post("/api/v1/tasks", "{\"title\":\"第一个标题\"}", headers);
        HttpTestClient.Response conflict = http.post("/api/v1/tasks", "{\"title\":\"不同标题\"}", headers);

        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body()).contains("\"code\":40001");
        assertThat(taskRepository.findAll()).hasSize(1);
        assertThat(taskRepository.findAll().get(0).getTitle()).isEqualTo("第一个标题");
    }

    @Test
    @DisplayName("幂等键格式非法 → 400，零写入")
    void invalidIdempotencyKeyIsRejected() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/tasks", "{\"title\":\"x\"}",
                Map.of("Idempotency-Key", "short"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("未提供幂等键时按普通请求处理：两次调用创建两行")
    void withoutIdempotencyKeyNoDeduplication() throws Exception {
        http.post("/api/v1/tasks", "{\"title\":\"同名\"}");
        http.post("/api/v1/tasks", "{\"title\":\"同名\"}");

        assertThat(taskRepository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("删除也能幂等重放：同键二次删除不报 404")
    void deleteIsIdempotentWithKey() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("要删的", null, null, null, null)).id();
        Map<String, String> headers = Map.of("Idempotency-Key", "it-test-delete-0001");

        HttpTestClient.Response first = http.delete("/api/v1/tasks/" + id, headers);
        HttpTestClient.Response second = http.delete("/api/v1/tasks/" + id, headers);

        assertThat(first.status()).isEqualTo(200);
        assertThat(second.status()).isEqualTo(200);
        assertThat(extractData(second.body())).isEqualTo(extractData(first.body()));
        assertThat(taskRepository.existsById(id)).isFalse();
    }

    // ---------------------------------------------------------------- 服务层规则

    @Test
    @DisplayName("批量删除上限：超过 50 个被拒绝且零写入")
    void bulkDeleteRespectsLimit() {
        List<UUID> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(index -> UUID.randomUUID())
                .toList();

        assertThatThrownBy(() -> taskService.deleteAll(tooMany))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50");
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("批量删除只删已存在的行，返回实际影响行数")
    void bulkDeleteReturnsAffectedCount() {
        UUID existing = taskService.create(new CreateTaskRequest("存在", null, null, null, null)).id();
        List<UUID> ids = List.of(existing, UUID.randomUUID());

        int affected = taskService.deleteAll(ids);

        assertThat(affected).isEqualTo(1);
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("分页参数被强制收敛：size 超上限收敛到 MAX_SIZE，非正收敛到默认值；page 负值归零")
    void queryPagingParamsAreNormalized() {
        assertThat(new TaskQuery(null, null, null, 0, 100_000).size()).isEqualTo(TaskQuery.MAX_SIZE);
        assertThat(new TaskQuery(null, null, null, 0, 0).size()).isEqualTo(TaskQuery.DEFAULT_SIZE);
        assertThat(new TaskQuery(null, null, null, -5, 10).page()).isZero();
    }

    @Test
    @DisplayName("分页：page/size 切片且 total 报告全量——不再有静默截断")
    void listIsPaginatedWithTotal() throws Exception {
        for (int i = 1; i <= 5; i++) {
            taskService.create(new CreateTaskRequest("任务" + i, null, null, null, null));
        }

        String firstPage = http.get("/api/v1/tasks?page=0&size=2").body();
        assertThat(firstPage).contains("\"total\":5").contains("\"page\":0").contains("\"size\":2");

        String secondPage = http.get("/api/v1/tasks?page=2&size=2").body();
        assertThat(secondPage).contains("\"total\":5");

        String beyond = http.get("/api/v1/tasks?page=9&size=2").body();
        assertThat(beyond).contains("\"items\":[]").contains("\"total\":5");
    }

    @Test
    @DisplayName("显示顺序：未完成在前，其次按截止时间升序（无截止时间排最后）")
    void searchOrdersUnfinishedFirstThenByDueDate() {
        taskService.create(new CreateTaskRequest("已完成", null, null, null, null));
        taskRepository.findAll().forEach(task -> {
            if ("已完成".equals(task.getTitle())) {
                taskService.setCompleted(task.getId(), true);
            }
        });
        taskService.create(new CreateTaskRequest("无截止时间", null, null, null, null));
        taskService.create(new CreateTaskRequest("明天到期", null, null, Instant.now().plusSeconds(86400), null));
        taskService.create(new CreateTaskRequest("今天到期", null, null, Instant.now().plusSeconds(3600), null));

        List<String> titles = taskService.search(TaskQuery.all()).items().stream()
                .map(dto -> dto.title()).toList();

        assertThat(titles).containsExactly("今天到期", "明天到期", "无截止时间", "已完成");
    }

    @Test
    @DisplayName("不存在的任务：查询/修改均抛资源不存在")
    void missingTaskRaisesNotFound() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> taskService.get(missing)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskService.update(missing,
                new UpdateTaskRequest("x", null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskService.delete(missing)).isInstanceOf(ResourceNotFoundException.class);
    }

    private static String extractData(String json) {
        int index = json.indexOf("\"data\":");
        assertThat(index).isGreaterThanOrEqualTo(0);
        int end = json.indexOf(",\"timestamp\"", index);
        return end < 0 ? json.substring(index) : json.substring(index, end);
    }
}
