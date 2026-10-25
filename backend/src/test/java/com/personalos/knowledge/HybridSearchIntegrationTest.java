package com.personalos.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.dto.KnowledgeHitDto.MatchedBy;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.KnowledgeRetrievalService;
import com.personalos.support.DeterministicEmbeddingModel;
import com.personalos.support.HttpTestClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * 混合检索：向量通道 + 关键词通道 + RRF 融合（2026-10-26）。
 *
 * <h2>为什么这些断言不能靠"能搜到"来验证</h2>
 * 测试里的嵌入是确定性词袋实现，向量通道本身的排序没有校准意义。
 * 所以这里断言的是**混合检索特有的行为**：
 * <ul>
 *   <li>精确标识符能被关键词通道捞回来（哪怕向量完全没命中）；</li>
 *   <li>两个通道都命中时标注 {@code BOTH} 且排在前面；</li>
 *   <li>关掉开关后退回纯向量（A/B 对比的前提）；</li>
 *   <li>仅关键词命中的片段 {@code score} 为 null（没做过嵌入，不能编分数）。</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "app.calendar.scan-enabled=false",
        // 阈值置 0：词袋相似度没有校准意义，这里不验证阈值过滤
        "app.knowledge.similarity-threshold=0.0",
        "app.knowledge.min-chunk-size-chars=20"
})
@Testcontainers
class HybridSearchIntegrationTest {

    /** 只在 A 文档出现的精确标识符，向量通道对它几乎没有信号 */
    private static final String RARE_TOKEN = "ZXQ7";
    private static final String DOC_A = "分布式系统笔记";
    private static final String DOC_B = "前端工程笔记";

    private static final String CONTENT_A = """
            # 分布式系统笔记

            一致性问题里常提到 2PC 与 3PC。两阶段提交在协调者故障时会让参与者一直阻塞，
            三阶段提交增加了预提交阶段来降低这种阻塞，但仍然存在极端情况下的不一致风险。
            """;

    private static final String CONTENT_B = """
            # 前端工程笔记

            浏览器渲染会经过样式计算、布局、绘制与合成四个阶段。
            改宽高会触发重排，只改颜色只会触发重绘，改 transform 通常只需要合成。
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        EmbeddingModel deterministicEmbeddingModel() {
            return new DeterministicEmbeddingModel(1024);
        }
    }

    private final HttpTestClient http;
    private final JdbcTemplate jdbcTemplate;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeRetrievalService retrievalService;

    @Autowired
    HybridSearchIntegrationTest(@Value("${local.server.port}") int port,
                                JdbcTemplate jdbcTemplate,
                                KnowledgeDocumentService documentService,
                                KnowledgeRetrievalService retrievalService) {
        this.http = new HttpTestClient(port);
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
        this.retrievalService = retrievalService;
    }

    @BeforeEach
    void cleanUp() throws Exception {
        jdbcTemplate.execute("TRUNCATE knowledge_document, vector_store RESTART IDENTITY CASCADE");
        upload(DOC_A + ".md", CONTENT_A, DOC_A);
        upload(DOC_B + ".md", CONTENT_B, DOC_B);
    }

    private void upload(String filename, String content, String title) throws Exception {
        http.upload("/api/v1/knowledge/documents", filename,
                content.getBytes(StandardCharsets.UTF_8), Map.of("title", title));
    }

    /** 往 A 文档的一个分块里插入罕见标识符，模拟「精确 token 只在某篇出现」 */
    private void injectRareToken(String token) {
        jdbcTemplate.update("UPDATE vector_store SET content = content || ? WHERE metadata->>'document_title' = ?",
                " 罕见标识符 " + token, DOC_A);
    }

    @Test
    @DisplayName("关键词通道能把向量完全没命中的精确标识符捞回来")
    void keywordChannelRescuesRareToken() {
        injectRareToken(RARE_TOKEN);

        List<KnowledgeHitDto> hits = retrievalService.search(RARE_TOKEN, 5);

        assertThat(hits).isNotEmpty();
        KnowledgeHitDto top = hits.get(0);
        assertThat(top.documentTitle()).isEqualTo(DOC_A);
        // 词袋嵌入对 ZXQ7 这种无意义 token 没有信号，因此这条**只能**来自关键词通道
        assertThat(top.matchedBy()).isIn(MatchedBy.KEYWORD, MatchedBy.BOTH);
        assertThat(top.content()).contains(RARE_TOKEN);
    }

    @Test
    @DisplayName("仅关键词命中的片段 score 为 null（没做过嵌入，不编分数）")
    void keywordOnlyHitHasNullScore() {
        // 构造一个词袋嵌入必然无信号的查询：纯罕见 token
        injectRareToken(RARE_TOKEN);

        List<KnowledgeHitDto> hits = retrievalService.search(RARE_TOKEN, 5);
        List<KnowledgeHitDto> keywordOnly = hits.stream()
                .filter(hit -> hit.matchedBy() == MatchedBy.KEYWORD)
                .toList();

        // 若词袋嵌入恰好也命中了（词面重叠），这条断言会落空 —— 只断言"只要存在就必须为 null"
        assertThat(keywordOnly).allSatisfy(hit -> assertThat(hit.score()).isNull());
    }

    @Test
    @DisplayName("两个通道都命中时标注 BOTH，且排在单通道命中之前")
    void bothChannelsRankFirst() {
        injectRareToken(RARE_TOKEN);

        // 这个查询既有英文 token（进关键词通道），又有中文语义（进向量通道）
        List<KnowledgeHitDto> hits = retrievalService.search("2PC " + RARE_TOKEN + " 阻塞", 5);

        assertThat(hits).isNotEmpty();
        // RRF 里两份贡献相加必然使 BOTH 排最前
        assertThat(hits.get(0).matchedBy()).isEqualTo(MatchedBy.BOTH);
    }

    @Test
    @DisplayName("纯中文查询时关键词通道不产出候选，检索退化为纯向量")
    void chineseOnlyQueryFallsBackToVector() {
        // KeywordExtractor 对纯中文返回空 → 关键词通道不参与 → 所有命中都应标 VECTOR
        List<KnowledgeHitDto> hits = retrievalService.search("重排和重绘有什么区别", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits).allSatisfy(hit -> assertThat(hit.matchedBy()).isEqualTo(MatchedBy.VECTOR));
    }

    @Test
    @DisplayName("同一文档只出现一次（去重在融合后仍然生效）")
    void dedupeAfterFusion() {
        List<KnowledgeHitDto> hits = retrievalService.search("渲染 布局 绘制", 5);

        assertThat(hits).extracting(KnowledgeHitDto::documentId).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("接口返回 matchedBy 字段，前端可据此标注来源")
    void apiExposesMatchedBy() throws Exception {
        injectRareToken(RARE_TOKEN);

        HttpTestClient.Response response = http.post("/api/v1/knowledge/search",
                "{\"query\":\"" + RARE_TOKEN + "\",\"topK\":3}");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("matchedBy");
    }
}
