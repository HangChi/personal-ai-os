package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.KnowledgeMetadata;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

/**
 * 向量检索（纯向量相似度，本阶段不做混合检索与重排序）。
 *
 * <p>把检索单独抽成一个服务，是为了让「检索」与「生成」可以分开验证：
 * RAG 答非所问时，第一步永远是判断错的到底是检索还是生成。
 */
@Service
public class KnowledgeRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;

    public KnowledgeRetrievalService(VectorStore vectorStore, KnowledgeProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * 检索最相关的若干片段。
     *
     * @param query 检索词
     * @param topK  返回条数；为空或越界会被收敛到合法区间
     * @return 命中片段，按相似度倒序；可能为空（表示没有达到阈值的内容）
     */
    public List<KnowledgeHitDto> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索词不能为空");
        }

        SearchRequest request = SearchRequest.builder()
                .query(query.strip())
                .topK(properties.normalizeTopK(topK))
                // 阈值必须显式给：不设阈值会把毫不相关的内容也塞进上下文，
                // 模型就会「拿着无关材料硬答」
                .similarityThreshold(properties.similarityThreshold())
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        List<KnowledgeHitDto> hits = documents.stream().map(this::toHit).toList();
        log.debug("检索完成 query={} topK={} threshold={} hits={}",
                query, request.getTopK(), request.getSimilarityThreshold(), hits.size());
        return hits;
    }

    private KnowledgeHitDto toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeHitDto(
                parseUuid(metadata.get(KnowledgeMetadata.DOCUMENT_ID)),
                asString(metadata.get(KnowledgeMetadata.DOCUMENT_TITLE)),
                asString(metadata.get(KnowledgeMetadata.FILENAME)),
                asInt(metadata.get(KnowledgeMetadata.CHUNK_INDEX)),
                document.getText(),
                document.getScore());
    }

    private static UUID parseUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : value.toString();
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
