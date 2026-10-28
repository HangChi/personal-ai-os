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
import java.util.ArrayList;
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
    @DisplayName("排序下推到 SQL 后：逐页取完必须不重不漏，且与一次性全量顺序一致")
    void paginationCoversEveryTaskExactlyOnce() {
        // 2026-10-28 第 10 项把排序从内存 comparator 搬到 SQL。这个改动最容易引入的
        // bug 是「并列键没有全序兜底」—— 数据库对并列行的返回顺序未定义，翻页时同一行
        // 可能出现在两页里，另一行谁也没出现。旧实现（全量加载后内存排序切片）天然不会
        // 犯这个错，所以只有专门测它才可能被发现。
        //
        // 刻意造大量并列：状态、截止时间全都相同，逼出「只能靠主键兜底」的场景。
        //
        // 诚实的边界：实测把 ORDER BY 末尾的 id 兜底键删掉，本用例**仍然通过** ——
        // PG 在这种小表上恰好返回稳定顺序。也就是说本用例锁住的是「翻页不重不漏」这个
        // 可观测结果，并不能证明兜底键是否必要。兜底键的真正价值在大表、并发写入、
        // 执行计划切换（如改走并行扫描）时才显现，那些场景在单机小数据下无法复现。
        // 保留它是因为成本为零、而一旦出问题极难定位；不要因为这条测试绿了就以为
        // 删掉兜底键是安全的。
        Instant sameDue = Instant.now().plusSeconds(86_400);
        for (int i = 0; i < 25; i++) {
            taskService.create(new CreateTaskRequest("并列" + i, null, null, sameDue, null));
        }

        long total = taskService.search(TaskQuery.all()).total();
        assertThat(total).isEqualTo(25);

        List<UUID> pagedIds = new ArrayList<>();
        int pageSize = 4;
        for (int page = 0; page * pageSize < total; page++) {
            taskService.search(new TaskQuery(null, null, null, page, pageSize)).items()
                    .forEach(dto -> pagedIds.add(dto.id()));
        }

        List<UUID> allIds = taskService.search(new TaskQuery(null, null, null, 0, TaskQuery.MAX_SIZE))
                .items().stream().map(dto -> dto.id()).toList();

        assertThat(pagedIds)
                .as("逐页拼起来必须覆盖全部任务，且没有重复 —— 重复说明排序键并列时没有全序兜底")
                .hasSize((int) total)
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(allIds);
    }

    @Test
    @DisplayName("不存在的任务：查询/修改均抛资源不存在")
    void missingTaskRaisesNotFound() {        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> taskService.get(missing)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskService.update(missing,
                new UpdateTaskRequest("x", null, null, null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> taskService.delete(missing)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("全量统计：待办/逾期是全库聚合，与分页无关（审查 #16）")
    void statsAreAggregatedNotPaginated() throws Exception {
        // 造超过一页（20）的任务，验证统计不按当前页算
        for (int i = 0; i < 25; i++) {
            taskService.create(new CreateTaskRequest("任务" + i, null, null, null, null));
        }
        // 两个已逾期：截止时间在过去
        Instant past = Instant.now().minusSeconds(3600);
        taskService.create(new CreateTaskRequest("逾期A", null, null, past, null));
        taskService.create(new CreateTaskRequest("逾期B", null, null, past, null));
        // 一个已完成：不应计入待办，也不应计入逾期
        UUID doneId = taskService.create(new CreateTaskRequest("已完成", null, null, null, null)).id();
        taskService.setCompleted(doneId, true);

        HttpTestClient.Response response = http.get("/api/v1/tasks/stats");

        assertThat(response.status()).isEqualTo(200);
        // 待办 = 27（25 + 2 逾期，不含已完成）
        assertThat(response.body()).contains("\"pending\":27");
        assertThat(response.body()).contains("\"overdue\":2");
    }

    private static String extractData(String json) {
        int index = json.indexOf("\"data\":");
        assertThat(index).isGreaterThanOrEqualTo(0);
        int end = json.indexOf(",\"timestamp\"", index);
        return end < 0 ? json.substring(index) : json.substring(index, end);
    }

    // ---------------------------------------------------- 重开恢复完成前状态（P3）

    @Test
    @DisplayName("重开：进行中被误标完成后取消，回到进行中而非待办")
    void reopenRestoresDoingStatus() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("写方案", null, null, null, null)).id();
        // 先切到 DOING
        taskService.update(id, new UpdateTaskRequest(null, null, null, null, null, TaskStatus.DOING, null));

        taskService.setCompleted(id, true);
        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);

        HttpTestClient.Response response = http.post("/api/v1/tasks/" + id + "/reopen", "");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"DOING\"");
        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.DOING);
    }

    @Test
    @DisplayName("重开：原本是待办的任务仍然回到待办")
    void reopenTodoStaysTodo() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("买牛奶", null, null, null, null)).id();
        taskService.setCompleted(id, true);

        taskService.setCompleted(id, false);

        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    @DisplayName("重开：重复完成不覆盖完成前状态（DOING → DONE → DONE → 取消仍是 DOING）")
    void repeatedCompleteDoesNotOverwritePreviousStatus() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("写方案", null, null, null, null)).id();
        taskService.update(id, new UpdateTaskRequest(null, null, null, null, null, TaskStatus.DOING, null));

        taskService.setCompleted(id, true);
        // 第二次「完成」是幂等操作，不能把 previousStatus 刷成 DONE
        taskService.setCompleted(id, true);
        taskService.setCompleted(id, false);

        assertThat(taskRepository.findById(id).orElseThrow().getStatus())
                .as("重复完成后取消，应回到 DOING；刷成 DONE 会让 reopen 毫无变化")
                .isEqualTo(TaskStatus.DOING);
    }

    @Test
    @DisplayName("重开：取消后再完成，完成前状态重新按当前状态记录")
    void previousStatusIsRecomputedAfterReopen() throws Exception {
        UUID id = taskService.create(new CreateTaskRequest("写方案", null, null, null, null)).id();
        taskService.update(id, new UpdateTaskRequest(null, null, null, null, null, TaskStatus.DOING, null));
        taskService.setCompleted(id, true);
        taskService.setCompleted(id, false);   // 回到 DOING

        // 改为 TODO 后再完成，previousStatus 应变成 TODO
        taskService.update(id, new UpdateTaskRequest(null, null, null, null, null, TaskStatus.TODO, null));
        taskService.setCompleted(id, true);
        taskService.setCompleted(id, false);

        assertThat(taskRepository.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
    }
}
