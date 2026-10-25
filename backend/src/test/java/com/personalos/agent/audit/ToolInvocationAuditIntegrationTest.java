package com.personalos.agent.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.common.idempotency.OperationSource;
import com.personalos.support.HttpTestClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 工具审计列表的集成测试。
 *
 * <p><b>本类存在的核心目的</b>：守住「过滤必须发生在分页之前」。
 * 阶段 11 之前的实现是先按 {@code PageRequest} 取一页、再在内存里剔除 HTTP 记录 ——
 * 那种写法下「一页凑不满」和「total 无从得知」都不会抛异常，
 * 只有在「HTTP 记录比 TOOL 记录更新」的数据分布下才会暴露。
 * 所以这里的用例刻意让 HTTP 记录时间更新。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none", "app.calendar.scan-enabled=false"})
@Testcontainers
class ToolInvocationAuditIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    private static final Instant BASE = Instant.parse("2026-10-25T00:00:00Z");

    private final HttpTestClient http;
    private final OperationRecordRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    ToolInvocationAuditIntegrationTest(@Value("${local.server.port}") int port,
                                       OperationRecordRepository repository) {
        this.http = new HttpTestClient(port);
        this.repository = repository;
    }

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    private void record(String key, OperationSource source, IdempotencyOutcome outcome,
                        String operation, Instant createdAt) {
        OperationRecordEntity entity = new OperationRecordEntity(
                key, source, operation, "digest", null, null, createdAt);
        entity.complete(outcome, "{}", 12);
        repository.save(entity);
    }

    private JsonNode getData(String path) throws Exception {
        HttpTestClient.Response response = http.get(path);
        assertThat(response.status()).isEqualTo(200);
        return mapper.readTree(response.body()).path("data");
    }

    @Test
    @DisplayName("过滤在分页前：HTTP 记录更新时，第 1 页仍只含 TOOL 且 total 正确")
    void shouldFilterBeforePaging() throws Exception {
        // TOOL 记录更早（会被旧实现取一页后再丢掉）
        record("t-1", OperationSource.TOOL, IdempotencyOutcome.SUCCESS, "findTasks", BASE.plusSeconds(1));
        record("t-2", OperationSource.TOOL, IdempotencyOutcome.SUCCESS, "createTask", BASE.plusSeconds(2));
        // HTTP 记录更新，占据「最新一页」
        record("h-1", OperationSource.HTTP, IdempotencyOutcome.SUCCESS, "POST /api/v1/tasks", BASE.plusSeconds(10));
        record("h-2", OperationSource.HTTP, IdempotencyOutcome.SUCCESS, "POST /api/v1/tasks", BASE.plusSeconds(11));
        record("h-3", OperationSource.HTTP, IdempotencyOutcome.SUCCESS, "POST /api/v1/tasks", BASE.plusSeconds(12));

        JsonNode data = getData("/api/v1/agent/tool-invocations?page=0&size=2");

        assertThat(data.path("total").asLong()).isEqualTo(2);
        JsonNode items = data.path("items");
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item ->
                assertThat(item.path("source").asText()).isEqualTo("TOOL"));
        // 顺序：时间倒序 → createTask（t=2s）在 findTasks（t=1s）之前
        assertThat(items.get(0).path("operation").asText()).isEqualTo("createTask");
        assertThat(items.get(1).path("operation").asText()).isEqualTo("findTasks");
    }

    @Test
    @DisplayName("outcome 筛选：total 反映过滤后的真实条数")
    void shouldFilterByOutcome() throws Exception {
        record("t-1", OperationSource.TOOL, IdempotencyOutcome.SUCCESS, "findTasks", BASE.plusSeconds(1));
        record("t-2", OperationSource.TOOL, IdempotencyOutcome.FAILED, "createTask", BASE.plusSeconds(2));
        record("t-3", OperationSource.TOOL, IdempotencyOutcome.REQUIRES_CONFIRMATION, "deleteTask", BASE.plusSeconds(3));

        JsonNode failed = getData("/api/v1/agent/tool-invocations?outcome=FAILED");
        assertThat(failed.path("total").asLong()).isEqualTo(1);
        assertThat(failed.path("items").get(0).path("operation").asText()).isEqualTo("createTask");

        JsonNode all = getData("/api/v1/agent/tool-invocations");
        assertThat(all.path("total").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("size 越界收敛到上限，不报错")
    void shouldClampSize() throws Exception {
        for (int index = 0; index < 3; index++) {
            record("t-" + index, OperationSource.TOOL, IdempotencyOutcome.SUCCESS,
                    "findTasks", BASE.plusSeconds(index));
        }

        JsonNode data = getData("/api/v1/agent/tool-invocations?size=1000");

        assertThat(data.path("size").asInt()).isEqualTo(ToolInvocationQuery.MAX_SIZE);
        assertThat(data.path("items")).hasSize(3);
    }

    @Test
    @DisplayName("未知 outcome：400 + 参数错误码，不静默忽略")
    void shouldRejectUnknownOutcome() throws Exception {
        HttpTestClient.Response response = http.get("/api/v1/agent/tool-invocations?outcome=NOPE");

        assertThat(response.status()).isEqualTo(400);
        assertThat(mapper.readTree(response.body()).path("code").asInt()).isEqualTo(40000);
    }

    @Test
    @DisplayName("同一时刻的多条记录：翻页不重复、不遗漏（id 作为决胜排序）")
    void shouldPageStablyAcrossSameTimestamp() throws Exception {
        for (int index = 0; index < 4; index++) {
            // 故意全部使用同一个 Instant —— 只按 createdAt 排序时顺序不稳定
            record("t-" + index, OperationSource.TOOL, IdempotencyOutcome.SUCCESS, "findTasks", BASE);
        }

        List<Long> ids = new ArrayList<>();
        for (int page = 0; page < 2; page++) {
            JsonNode items = getData("/api/v1/agent/tool-invocations?page=" + page + "&size=2").path("items");
            items.forEach(item -> ids.add(item.path("id").asLong()));
        }

        assertThat(ids).hasSize(4);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("空库：返回空列表与 total=0，不是 404")
    void shouldReturnEmptyPage() throws Exception {
        JsonNode data = getData("/api/v1/agent/tool-invocations");

        assertThat(data.path("total").asLong()).isZero();
        assertThat(data.path("items")).isEmpty();
    }
}
