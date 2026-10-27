package com.personalos.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.service.VectorStoreInspector;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 数据导入集成测试（真实数据库 + 真实向量库）。
 *
 * <p><b>必须绑 Testcontainers</b>：这个测试的 {@code @BeforeEach} 会清空六张表。
 * 若漏掉容器配置，{@code @SpringBootTest} 会退回 application.yml 里的
 * {@code localhost:55432} —— 也就是开发者的**真实数据库**，运行测试等于清库。
 * 这类事故一旦发生只能靠备份恢复，所以除了容器配置，下面还有一道显式的库名断言。
 *
 * <p>用 {@code DELETE} 清表而不是事务回滚：向量写入走的是独立连接，事务回滚带不走它 ——
 * 用回滚的测试会留下脏向量，后面「按 document_id 计数」的断言就会飘。
 */
@SpringBootTest(properties = {
        // 关掉自动装配的模型，否则会与下面的桩模型同时存在，
        // PgVectorStore 拿不到唯一的 EmbeddingModel（NoUniqueBeanDefinition）
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Testcontainers
class DataImportIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    /**
     * 嵌入模型替换为词袋实现：导入知识库要重建向量，否则会去连本机 Ollama。
     * 用确定性实现是为了让「向量条数」这类断言可重复。
     */
    @TestConfiguration
    static class StubConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }
    }

    private static final UUID TASK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMORY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOC_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Autowired
    private DataImportService importService;

    @Autowired
    private VectorStoreInspector inspector;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        // 护栏：确认连的是 Testcontainers 的临时库。库名不是 test 就直接失败 ——
        // 宁可整个测试红，也不能把清表语句跑在开发者的真实数据上。
        String database = jdbc.queryForObject("select current_database()", String.class);
        if (!"test".equals(database)) {
            throw new IllegalStateException(
                    "拒绝在非测试库上执行清理（当前库：" + database + "）。请检查 Testcontainers 配置。");
        }
        jdbc.execute("DELETE FROM vector_store");
        jdbc.execute("DELETE FROM knowledge_document");
        jdbc.execute("DELETE FROM chat_message");
        jdbc.execute("DELETE FROM chat_session");
        jdbc.execute("DELETE FROM task_tag");
        jdbc.execute("DELETE FROM task");
        jdbc.execute("DELETE FROM calendar_event");
        jdbc.execute("DELETE FROM memory");
    }

    /** 一份覆盖全部五个模块的最小导出文件 */
    private String fullPayload() {
        return """
                {
                  "formatVersion": "1",
                  "sessions": [
                    {
                      "session": {
                        "conversationId": "import-c1",
                        "title": "导入进来的会话",
                        "createdAt": "2026-10-01T00:00:00Z",
                        "updatedAt": "2026-10-01T00:10:00Z"
                      },
                      "messages": [
                        {"role": "USER", "content": "第一问", "sequenceNo": 1, "createdAt": "2026-10-01T00:00:00Z"},
                        {"role": "ASSISTANT", "content": "第一答", "sequenceNo": 2, "createdAt": "2026-10-01T00:01:00Z"}
                      ]
                    }
                  ],
                  "tasks": [
                    {
                      "id": "%s", "title": "导入的任务", "description": "说明",
                      "status": "DONE", "priority": "HIGH", "tags": ["导入", "回归"],
                      "dueAt": "2026-11-01T09:00:00Z", "completedAt": "2026-10-20T10:00:00Z",
                      "createdAt": "2026-10-02T08:00:00Z", "updatedAt": "2026-10-20T10:00:00Z"
                    }
                  ],
                  "calendarEvents": [
                    {
                      "id": "%s", "title": "导入的日程", "description": null, "location": "会议室",
                      "allDay": false, "startsAt": "2026-11-05T09:00:00Z", "endsAt": "2026-11-05T10:00:00Z",
                      "recurrenceType": "WEEKLY", "recurrenceUntil": null, "reminderLeadMinutes": 30,
                      "createdAt": "2026-10-03T08:00:00Z"
                    }
                  ],
                  "memories": [
                    {
                      "id": "%s", "content": "用户偏好简洁回答", "source": "MANUAL",
                      "createdAt": "2026-10-04T08:00:00Z", "updatedAt": "2026-10-04T08:00:00Z"
                    }
                  ],
                  "knowledgeDocuments": [
                    {
                      "document": {
                        "id": "%s", "title": "导入的文档", "filename": "imported.md",
                        "contentType": "text/markdown", "sizeBytes": 120, "charCount": 12,
                        "chunkCount": 2, "createdAt": "2026-10-05T08:00:00Z"
                      },
                      "chunks": ["第一段内容", "第二段内容"]
                    }
                  ]
                }
                """.formatted(TASK_ID, EVENT_ID, MEMORY_ID, DOC_ID);
    }

    @Test
    @DisplayName("五个模块全部还原，知识库按分块重建向量")
    void importsAllModules() {
        DataImportService.ImportResult result = importService.importAll(fullPayload());

        assertThat(result.sessions()).isEqualTo(1);
        assertThat(result.messages()).isEqualTo(2);
        assertThat(result.tasks()).isEqualTo(1);
        assertThat(result.calendarEvents()).isEqualTo(1);
        assertThat(result.memories()).isEqualTo(1);
        assertThat(result.documents()).isEqualTo(1);
        assertThat(result.vectors()).as("两个分块 → 两条向量").isEqualTo(2);

        // 对话：顺序号必须保留，否则对话会乱序
        assertThat(jdbc.queryForObject(
                "select title from chat_session where conversation_id = 'import-c1'", String.class))
                .isEqualTo("导入进来的会话");
        assertThat(jdbc.queryForObject(
                "select content from chat_message where conversation_id = 'import-c1' and sequence_no = 2",
                String.class))
                .isEqualTo("第一答");

        // 任务：id 与状态、标签都要还原（原 id 是引用其他数据的前提）
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, TASK_ID))
                .isEqualTo("DONE");
        assertThat(jdbc.queryForList("select tag from task_tag where task_id = ? order by tag_index",
                String.class, TASK_ID))
                .containsExactly("导入", "回归");

        // 日程：重复规则与提前量是「提醒能不能算对」的关键
        assertThat(jdbc.queryForObject(
                "select recurrence_type from calendar_event where id = ?", String.class, EVENT_ID))
                .isEqualTo("WEEKLY");

        assertThat(jdbc.queryForObject("select content from memory where id = ?", String.class, MEMORY_ID))
                .isEqualTo("用户偏好简洁回答");

        // 知识库：文档记录与向量条数必须一致（SC-001 的不变量）
        assertThat(jdbc.queryForObject(
                "select chunk_count from knowledge_document where id = ?", Integer.class, DOC_ID))
                .isEqualTo(2);
        assertThat(inspector.countByDocumentId(DOC_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("目标库非空时明确拒绝，而不是把数据混进去")
    void rejectsNonEmptyDatabase() {
        jdbc.update("insert into memory (id, content, created_at, updated_at, source) "
                + "values (?, '已有数据', now(), now(), 'MANUAL')", UUID.randomUUID());

        assertThatThrownBy(() -> importService.importAll(fullPayload()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("导入只支持空库");

        // 拒绝不能留下半截：会话不该被写进去
        assertThat(jdbc.queryForObject("select count(*) from chat_session", Integer.class)).isZero();
    }

    @Test
    @DisplayName("格式版本不匹配时拒绝 —— 免得把不认识的字段结构硬塞进库")
    void rejectsUnknownFormatVersion() {
        String future = fullPayload().replace("\"formatVersion\": \"1\"", "\"formatVersion\": \"99\"");

        assertThatThrownBy(() -> importService.importAll(future))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的导出格式版本");
    }

    @Test
    @DisplayName("脏数据不写进库：未知角色、空标题、非法状态都被跳过或回落")
    void sanitizesBadRows() {
        String payload = """
                {
                  "formatVersion": "1",
                  "sessions": [
                    {
                      "session": {"conversationId": "c-bad", "title": "", "createdAt": "2026-10-01T00:00:00Z"},
                      "messages": [
                        {"role": "SYSTEM", "content": "不该被接受", "sequenceNo": 1},
                        {"role": "USER", "content": "正常的", "sequenceNo": 2}
                      ]
                    }
                  ],
                  "tasks": [
                    {"id": "%s", "title": "状态非法的任务", "status": "WHATEVER", "priority": null}
                  ]
                }
                """.formatted(TASK_ID);

        DataImportService.ImportResult result = importService.importAll(payload);

        assertThat(result.messages()).as("SYSTEM 角色被跳过，只进 1 条").isEqualTo(1);
        assertThat(jdbc.queryForObject("select title from chat_session where conversation_id = 'c-bad'",
                String.class))
                .as("空标题回落为默认标题")
                .isEqualTo("导入的会话");
        assertThat(jdbc.queryForObject("select status from task where id = ?", String.class, TASK_ID))
                .as("非法状态回落到 TODO")
                .isEqualTo("TODO");
        assertThat(jdbc.queryForObject("select priority from task where id = ?", String.class, TASK_ID))
                .as("null 优先级回落到 MEDIUM")
                .isEqualTo("MEDIUM");
    }
}
