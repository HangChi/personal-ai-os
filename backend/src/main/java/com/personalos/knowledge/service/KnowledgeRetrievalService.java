package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.KnowledgeMetadata;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
     * <p><b>按文档去重</b>（2026-10-25，评测后新增）：{@code topK} 是按**片段**取的，
     * 而一篇切出 13 块的文档会一次性占掉 3–4 个位置，把只切出 1–3 块的正确文档挤出榜
     * （评测中 {@code HTTP 渐进} 10 块、{@code MySQL 事务} 13 块反复出现这个现象）。
     *
     * <p>做法：多取一些片段 → 每篇只留最高分那条 → 按文档分排序 → 截到 {@code topK}。
     * 同一文档的其他片段信息量低于它自己最高的那条，所以 Recall 基本不变，
     * 而 Precision 会明显改善。
     *
     * @param query 检索词
     * @param topK  返回条数；为空或越界会被收敛到合法区间
     * @return 命中片段，按相似度倒序；可能为空（表示没有达到阈值的内容）
     */
    public List<KnowledgeHitDto> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索词不能为空");
        }

        int limit = properties.normalizeTopK(topK);
        // 去重要有多余的片段可用，否则前 5 条若全属同一文档，去重后只剩 1 条。
        // 3 倍是经验值：既足够覆盖常见分布，又不至于把上限撑到 MAX_TOP_K。
        int fetchLimit = Math.min(limit * 3, KnowledgeProperties.MAX_TOP_K);

        SearchRequest request = SearchRequest.builder()
                .query(query.strip())
                .topK(fetchLimit)
                // 阈值必须显式给：不设阈值会把毫不相关的内容也塞进上下文，
                // 模型就会「拿着无关材料硬答」
                .similarityThreshold(properties.similarityThreshold())
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        List<KnowledgeHitDto> hits = dedupeByDocument(documents).stream()
                .sorted(Comparator.comparingDouble(KnowledgeHitDto::score).reversed())
                .limit(limit)
                .toList();
        log.debug("检索完成 query={} topK={} fetched={} hits={}",
                query, limit, documents.size(), hits.size());
        return hits;
    }

    /**
     * 每篇文档只保留得分最高的那条片段。
     *
     * <p>用 {@code TreeMap} 保持插入序（即得分降序的大致顺序），随后仍显式排序 ——
     * 因为插入序只反映第一次出现的顺序，不保证整体有序。
     */
    private List<KnowledgeHitDto> dedupeByDocument(List<Document> documents) {
        Map<UUID, KnowledgeHitDto> best = new TreeMap<>();
        for (Document document : documents) {
            Map<String, Object> metadata = document.getMetadata();
            UUID documentId = parseUuid(metadata.get(KnowledgeMetadata.DOCUMENT_ID));
            if (documentId == null) {
                // 没有 document_id 的片段（理论上不存在，框架总会写）不去重，直接保留
                continue;
            }
            KnowledgeHitDto hit = toHit(document);
            KnowledgeHitDto existing = best.get(documentId);
            if (existing == null || hit.score() > existing.score()) {
                best.put(documentId, hit);
            }
        }
        return new ArrayList<>(best.values());
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
