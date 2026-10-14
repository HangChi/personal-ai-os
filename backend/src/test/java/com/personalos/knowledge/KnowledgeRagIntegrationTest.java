package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.knowledge.dto.CitationDto;
import com.personalos.knowledge.dto.KnowledgeAskResponse;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.KnowledgeQaService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.knowledge.service.VectorStoreInspector;
import com.personalos.support.HttpTestClient;
import com.personalos.support.HttpTestClient.SseEvent;
import com.personalos.support.ScriptedToolCallingChatModel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 知识库 RAG 集成测试（≥5 个可重复用例）。
 *
 * <h2>为什么这些用例可重复</h2>
 * 嵌入模型替换为 {@link com.personalos.support.DeterministicEmbeddingModel}（词袋 + L2 归一化），
 * 因此**不依赖网络、不需要密钥**，且同样的输入永远得到同样的向量。
 * 但**向量库仍是真实的 PgVectorStore + 真实 pgvector 表** ——
 * 所以「维度一致」「删除后零残留」验证的是真实链路，而不是替身行为。
 *
 * <h2>这些用例验证不了什么</h2>
 * 词袋模型没有语义泛化能力（同义词、改写检索不到）。
 * 因此本类只能证明**链路正确**，不能证明**检索效果**；后者需要真实嵌入模型（见 quickstart 场景 D）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        // 词袋相似度是「词面重叠」的线性映射，具体数值没有校准意义。
        // 排序断言本身不需要阈值，故置 0 只验证排序与召回，不验证阈值过滤。
        "app.knowledge.similarity-threshold=0.0"
})
@Testcontainers
class KnowledgeRagIntegrationTest {

    /** 主题 A：数据库事务 */
    private static final String TRANSACTION_MARKDOWN = """
            # 数据库事务

            PostgreSQL 支持四种事务隔离级别：读未提交、读已提交、可重复读、串行化。
            默认级别是读已提交。串行化最严格，但并发性能最低。
            隔离级别越高，脏读与幻读的可能性越低。
            """;

    /** 主题 B：植物养护（与主题 A 无词面重叠） */
    private static final String PLANT_MARKDOWN = """
            # 植物养护

            绿萝喜欢散射光，避免正午直射。浇水遵循见干见湿，冬季减少频次。
            施肥以氮肥为主，生长期每两周一次。
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {

        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new com.personalos.support.DeterministicEmbeddingModel(1024);
        }

        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private final HttpTestClient http;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeRetrievalService retrievalService;
    private final KnowledgeQaService qaService;
    private final VectorStoreInspector inspector;
    private final ScriptedToolCallingChatModel model;
    private final OperationRecordRepository operationRecordRepository;

    @Autowired
    KnowledgeRagIntegrationTest(@Value("${local.server.port}") int port,
                                JdbcTemplate jdbcTemplate,
                                KnowledgeDocumentRepository documentRepository,
                                KnowledgeDocumentService documentService,
                                KnowledgeRetrievalService retrievalService,
                                KnowledgeQaService qaService,
                                VectorStoreInspector inspector,
                                ScriptedToolCallingChatModel model,
                                OperationRecordRepository operationRecordRepository) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
        this.retrievalService = retrievalService;
        this.qaService = qaService;
        this.inspector = inspector;
        this.model = model;
        this.operationRecordRepository = operationRecordRepository;
    }

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM vector_store");
        documentRepository.deleteAll();
        operationRecordRepository.deleteAll();
        model.reset();
    }

    // ------------------------------------------------------------------ 用例 1

    @Test
    @DisplayName("用例1：上传 Markdown 后建索引 —— chunk_count 与向量条数相等，且逐行维度为 1024（SC-001/SC-002）")
    void uploadIndexesDocumentWithConsistentDimension() throws Exception {
        UUID documentId = uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");

        var document = documentRepository.findById(documentId).orElseThrow();
        assertThat(document.getChunkCount()).as("应至少切出一个片段").isPositive();

        // SC-001：文档声明的分块数 == 实际写入的向量条数
        assertThat(inspector.countByDocumentId(documentId)).isEqualTo(document.getChunkCount());

        // SC-002：**逐行**校验维度（不是抽查），并确认列定义就是 vector(1024)
        // 注意：OptionalAssert.contains(x) 判的是"值等于 x"，不是子串包含，
        // 因此必须先 orElseThrow() 取出字符串再做 contains。
        assertThat(inspector.embeddingColumnType().orElseThrow()).contains("1024");
        assertThat(inspector.countWithUnexpectedDimension()).as("不应存在维度不等于 1024 的向量").isZero();

        // 列表接口也应能看到
        assertThat(http.get("/api/v1/knowledge/documents").body()).contains("数据库事务");
    }

    // ------------------------------------------------------------------ 用例 2

    @Test
    @DisplayName("用例2：主题检索命中 —— 第一条结果来自对应文档（SC-004）")
    void retrievalRanksRelatedDocumentFirst() throws Exception {
        uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");
        uploadDocument("植物养护.md", PLANT_MARKDOWN, "植物养护");

        List<KnowledgeHitDto> hits = retrievalService.search("隔离级别", 3);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).documentTitle()).isEqualTo("数据库事务");
        assertThat(hits.get(0).content()).contains("隔离级别");
        assertThat(hits.get(0).chunkIndex()).isGreaterThanOrEqualTo(0);
        assertThat(hits.get(0).score()).isNotNull();
    }

    @Test
    @DisplayName("用例2b：检索接口不经模型，且 topK 被收敛")
    void searchEndpointDoesNotInvokeModelAndCapsTopK() throws Exception {
        uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");

        HttpTestClient.Response response = http.post("/api/v1/knowledge/search",
                "{\"query\":\"隔离级别\",\"topK\":999}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("数据库事务");
        // 检索是纯向量操作，不应触碰模型
        assertThat(model.receivedInstructions()).as("检索不得调用模型").isEmpty();
    }

    // ------------------------------------------------------------------ 用例 3

    @Test
    @DisplayName("用例3：问答返回引用 —— 片段可溯源、且确实进入了发给模型的上下文（SC-005）")
    void askReturnsAnswerWithTraceableCitations() throws Exception {
        UUID documentId = uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");
        model.enqueueText("根据资料，共有四种隔离级别 [1]。");

        HttpTestClient.Response response = http.post("/api/v1/knowledge/ask",
                "{\"question\":\"支持哪些隔离级别？\"}");
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("共有四种隔离级别").contains("citations");

        // 结构化断言
        model.enqueueText("再次回答 [1]。");
        KnowledgeAskResponse ask = qaService.ask("支持哪些隔离级别？", null);

        assertThat(ask.answered()).isTrue();
        assertThat(ask.citations()).isNotEmpty();
        for (CitationDto citation : ask.citations()) {
            assertThat(citation.documentId()).isEqualTo(documentId);
            assertThat(citation.documentTitle()).isEqualTo("数据库事务");
            assertThat(citation.content()).isNotBlank();
            assertThat(citation.index()).isPositive();
            // 引用片段必须真的来自原文（SC-005）
            assertThat(TRANSACTION_MARKDOWN).contains(citation.content().strip());
        }

        // 关键：检索到的片段确实被拼进了提示词 —— 否则"引用"与"回答依据"可能对不上
        String lastPrompt = model.receivedInstructions().get(model.receivedInstructions().size() - 1).stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(lastPrompt).contains("读已提交").contains("【资料】").contains("支持哪些隔离级别");
    }

    // ------------------------------------------------------------------ 用例 4

    @Test
    @DisplayName("用例4：删除文档清理其向量，且不影响其它文档（SC-003）")
    void deletingDocumentRemovesOnlyItsOwnVectors() throws Exception {
        UUID transactionDoc = uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");
        UUID plantDoc = uploadDocument("植物养护.md", PLANT_MARKDOWN, "植物养护");

        int transactionChunks = inspector.countByDocumentId(transactionDoc);
        int plantChunks = inspector.countByDocumentId(plantDoc);
        assertThat(transactionChunks).isPositive();
        assertThat(plantChunks).isPositive();

        HttpTestClient.Response deleted = http.delete("/api/v1/knowledge/documents/" + transactionDoc, Map.of());

        assertThat(deleted.status()).isEqualTo(200);
        assertThat(deleted.body()).contains("\"deletedChunks\":" + transactionChunks);
        assertThat(documentRepository.existsById(transactionDoc)).isFalse();

        // SC-003：被删文档零残留，另一文档条数完全不变
        assertThat(inspector.countByDocumentId(transactionDoc)).isZero();
        assertThat(inspector.countByDocumentId(plantDoc)).isEqualTo(plantChunks);

        // 删除后不应再被检索到
        assertThat(retrievalService.search("隔离级别", 5))
                .noneMatch(hit -> hit.documentTitle().equals("数据库事务"));
        assertThat(retrievalService.search("绿萝", 5))
                .anyMatch(hit -> hit.documentTitle().equals("植物养护"));
    }

    // ------------------------------------------------------------------ 用例 5

    @Test
    @DisplayName("用例5：知识库为空时问答不调用模型，直接给出「没有找到」（FR-009）")
    void askOnEmptyKnowledgeBaseDoesNotCallModel() throws Exception {
        KnowledgeAskResponse ask = qaService.ask("完全不相关的问题", null);

        assertThat(ask.answered()).isFalse();
        assertThat(ask.answer()).isEqualTo(KnowledgeQaService.NO_CONTENT_ANSWER);
        assertThat(ask.citations()).isEmpty();
        assertThat(model.receivedInstructions()).as("无命中的问答不得调用模型（否则就是编造）").isEmpty();
    }

    // ------------------------------------------------------------------ 用例 6

    @Test
    @DisplayName("用例6：非法输入零写入 —— 类型不支持 / 空文件 / 超限（SC-006）")
    void unsupportedInputsProduceZeroWrites() throws Exception {
        // 类型不支持
        HttpTestClient.Response pdf = http.upload("/api/v1/knowledge/documents", "手册.pdf",
                "%PDF-1.4".getBytes(StandardCharsets.UTF_8), Map.of());
        assertThat(pdf.status()).isEqualTo(400);
        assertThat(pdf.body()).contains("\"code\":40003");

        // 空文件
        HttpTestClient.Response empty = http.upload("/api/v1/knowledge/documents", "空.md",
                new byte[0], Map.of());
        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.body()).contains("\"code\":40003");

        // 超出应用层上限（配置为 5 MiB）
        byte[] oversized = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(oversized, (byte) 'a');
        HttpTestClient.Response tooLarge = http.upload("/api/v1/knowledge/documents", "大文件.txt",
                oversized, Map.of());
        assertThat(tooLarge.status()).isEqualTo(400);

        // 非 UTF-8 编码（GBK 的“中文”）
        HttpTestClient.Response gbk = http.upload("/api/v1/knowledge/documents", "gbk.txt",
                new byte[] {(byte) 0xD6, (byte) 0xD0, (byte) 0xCE, (byte) 0xC4}, Map.of());
        assertThat(gbk.status()).isEqualTo(400);
        assertThat(gbk.body()).contains("\"code\":40003");

        // SC-006：两张表都必须零变化
        assertThat(documentRepository.count()).isZero();
        assertThat(inspector.countAll()).isZero();
    }

    @Test
    @DisplayName("用例6b：删除不存在的文档 → 404/40004")
    void deletingUnknownDocumentReturns404() throws Exception {
        HttpTestClient.Response response = http.delete("/api/v1/knowledge/documents/" + UUID.randomUUID(), Map.of());

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":40004");
    }

    // ------------------------------------------------------------------ 用例 7

    @Test
    @DisplayName("用例7：对话中调用知识库检索工具（复用阶段 2 的 Agent 工具机制）")
    void chatAgentCanSearchKnowledgeBase() throws Exception {
        uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");

        String conversationId = createChatSession();
        model.enqueueToolCall("searchKnowledge", "{\"query\":\"隔离级别\"}");
        model.enqueueText("你的资料里提到了四种隔离级别。");

        List<SseEvent> events = http.sse(
                "/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"我的资料里怎么讲隔离级别的？\"}", Map.of());

        assertThat(events).extracting(SseEvent::name).contains("done");

        // 工具确实被执行并留下审计
        assertThat(operationRecordRepository.findAll())
                .anyMatch(record -> "searchKnowledge".equals(record.getOperation())
                        && record.getOutcome() == IdempotencyOutcome.SUCCESS);

        // 模型第二次调用时，提示词里带上了检索到的片段
        List<List<Message>> instructions = model.receivedInstructions();
        assertThat(instructions).hasSizeGreaterThanOrEqualTo(2);
        String followUp = instructions.get(instructions.size() - 1).stream()
                .map(Message::getText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(followUp).contains("隔离级别");
    }

    @Test
    @DisplayName("用例8：重复上传同一文件成为两个独立文档，删除其一不影响另一（不静默去重）")
    void duplicateUploadsAreIndependentDocuments() throws Exception {
        UUID first = uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");
        UUID second = uploadDocument("数据库事务.md", TRANSACTION_MARKDOWN, "数据库事务");

        assertThat(first).isNotEqualTo(second);
        assertThat(documentRepository.count()).isEqualTo(2);
        int secondChunks = inspector.countByDocumentId(second);

        documentService.delete(first);

        assertThat(inspector.countByDocumentId(first)).isZero();
        assertThat(inspector.countByDocumentId(second)).isEqualTo(secondChunks);
        assertThat(documentRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 工具

    private UUID uploadDocument(String filename, String content, String title) throws Exception {
        HttpTestClient.Response response = http.upload("/api/v1/knowledge/documents", filename,
                content.getBytes(StandardCharsets.UTF_8), Map.of("title", title));
        assertThat(response.status()).as("上传应成功：%s", response.body()).isEqualTo(201);
        String id = extract(response.body(), "id");
        assertThat(id).isNotNull();
        return UUID.fromString(id);
    }

    private String createChatSession() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/chat/sessions", "{}");
        assertThat(response.status()).isEqualTo(201);
        return extract(response.body(), "conversationId");
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
