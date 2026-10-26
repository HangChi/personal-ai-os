package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.knowledge.KnowledgeMetadata;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeHitDto;
import com.personalos.knowledge.dto.KnowledgeHitDto.MatchedBy;
import com.personalos.knowledge.spi.KnowledgeSearchPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 知识库检索：向量通道 + 关键词通道，用 RRF 融合（2026-10-26 起）。
 *
 * <h2>为什么要混合检索</h2>
 * 评测（{@code scripts/eval/}）里 6 条未命中全部落在两类查询上：
 * <ol>
 *   <li><b>精确标识符 / 短英文词</b>：{@code 3PC}、{@code ip_hash}、{@code unknown}、{@code watch}。
 *       这类 token 在嵌入空间里几乎没有语义，纯向量检索天然弱；</li>
 *   <li><b>跨域概念</b>：「锁」「事务」「调度」在多个领域都存在，向量分不清
 *       「MySQL 行锁」与「分布式锁」。</li>
 * </ol>
 * 关键词通道能精确命中前者；后者仍属嵌入模型的能力边界，混合检索救不了。
 *
 * <h2>为什么用 RRF 而不是加权分数</h2>
 * 两个通道的分数**不可比**：向量是 cosine 相似度（0~1），关键词通道只有排名、没有分数。
 * 强行归一化会引入一个凭空捏造的换算系数。RRF（{@code Σ 1/(k+rank)}）只用排名，
 * 不需要任何归一化 —— 这是它能成为业界默认做法的原因。
 *
 * <h2>阈值与关键词通道的关系</h2>
 * 相似度阈值只作用于**向量通道**（它是 cosine 的下限）。关键词通道不设阈值：
 * 它本身就是「精确子串命中」，命中了就是命中了，没有「相似度不足」这回事。
 * 因此可能出现「向量通道全部低于阈值、但关键词通道有命中」的情况 —— 这正是我们要的：
 * 纯向量会返回空，混合检索能给出结果。
 */
@Service
public class KnowledgeRetrievalService implements KnowledgeSearchPort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final VectorStore vectorStore;
    private final VectorStoreInspector inspector;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeRetrievalService(VectorStore vectorStore,
                                     VectorStoreInspector inspector,
                                     KnowledgeProperties properties,
                                     ObjectMapper objectMapper) {
        this.vectorStore = vectorStore;
        this.inspector = inspector;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 检索最相关的若干片段。
     *
     * <p><b>按文档去重</b>：{@code topK} 原本按**片段**取，一篇 13 块的文档会一次占掉
     * 3–4 个位置，把只切出 1–3 块的正确文档挤出榜（评测实证：文档多样性 51% → 78%）。
     * 现在每篇只留排名最高那条，再按文档排序截断。
     *
     * @param query 检索词
     * @param topK  返回条数；为空或越界会被收敛到合法区间
     * @return 命中片段，按融合分倒序；可能为空（两个通道都没有命中）
     */
    public List<KnowledgeHitDto> search(String query, Integer topK) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "检索词不能为空");
        }

        int limit = properties.normalizeTopK(topK);
        // 去重要有多余的候选可用，否则前 5 条若全属同一文档，去重后只剩 1 条。
        int fetchLimit = Math.min(limit * 3, KnowledgeProperties.MAX_TOP_K);

        List<Document> vectorDocs = vectorSearch(query, fetchLimit);
        List<KeywordChannel> keywordChannels = keywordSearch(query, fetchLimit);

        if (keywordChannels.isEmpty()) {
            // 无关键词候选（纯中文查询很常见）：退化为纯向量，行为与混合检索前完全一致
            List<KnowledgeHitDto> hits = dedupeByDocument(vectorDocs).stream()
                    .sorted(Comparator.comparingDouble(KnowledgeHitDto::score).reversed())
                    .limit(limit)
                    .toList();
            log.debug("检索完成（纯向量） query={} fetched={} hits={}",
                    query, vectorDocs.size(), hits.size());
            return hits;
        }

        List<KnowledgeHitDto> fused = fuse(vectorDocs, keywordChannels);
        List<KnowledgeHitDto> hits = dedupeFused(fused).stream().limit(limit).toList();
        log.debug("检索完成（混合） query={} vector={} keywordChannels={} fused={} hits={}",
                query, vectorDocs.size(), keywordChannels.size(), fused.size(), hits.size());
        return hits;
    }

    // ------------------------------------------------------------------ 两个通道

    private List<Document> vectorSearch(String query, int fetchLimit) {
        SearchRequest request = SearchRequest.builder()
                .query(query.strip())
                .topK(fetchLimit)
                // 阈值必须显式给：不设阈值会把毫不相关内容塞进上下文，模型就会硬答
                .similarityThreshold(properties.similarityThreshold())
                .build();
        return vectorStore.similaritySearch(request);
    }

    /**
     * 关键词通道：**每个 token 单独一路**，各自带 IDF 权重。
     *
     * <p>为什么按 token 分开而不是拼成一条 OR 查询：拼在一起就丢失了「哪个 token 命中」
     * 的信息，而 {@code unknown}（罕见）与 {@code ip}（常见）的可信度本就不同。
     * 分开后可以在融合时让罕见 token 有更高的话语权。
     */
    private List<KeywordChannel> keywordSearch(String query, int fetchLimit) {
        if (!properties.hybridSearchEnabled()) {
            return List.of();
        }
        List<String> tokens = KeywordExtractor.extract(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        long total = inspector.countAllChunks();
        if (total <= 0) {
            return List.of();
        }
        List<KeywordChannel> channels = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            List<VectorStoreInspector.KeywordChunk> chunks = inspector.searchByKeyword(token, fetchLimit);
            if (chunks.isEmpty()) {
                continue;
            }
            channels.add(new KeywordChannel(token, idf(token, total), chunks));
        }
        return channels;
    }

    /**
     * IDF 权重，归一到 0~1：{@code log(total/(1+df)) / log(total)}。
     *
     * <ul>
     *   <li>token 只出现在极少片段 → 接近 1（高可信）</li>
     *   <li>token 出现在所有片段 → 接近 0（无区分度）</li>
     * </ul>
     *
     * <p>为什么必须归一化：RRF 里向量通道的贡献是 {@code 1/(k+rank)}，量级约 0.016。
     * 关键词通道要能与它并置比较，权重就必须落在同一量纲（0~1）。
     */
    private double idf(String token, long total) {
        long df = inspector.countBySubstring(token);
        if (total <= 1) {
            return 1.0;
        }
        double numerator = Math.log((double) total / (1 + df));
        double denominator = Math.log(total);
        if (denominator <= 0) {
            return 1.0;
        }
        // df 大于等于 total 时 numerator 可能为负，夹到 0
        return Math.max(0.0, Math.min(1.0, numerator / denominator));
    }

    /** 一个 token 对应的关键词通道 */
    private record KeywordChannel(String token, double idf,
                                  List<VectorStoreInspector.KeywordChunk> chunks) {
    }

    // ------------------------------------------------------------------ 融合

    /**
     * 一个候选片段在融合中的累计状态。
     *
     * <p>用可变对象是为了在遍历两个通道时原地累加 RRF 分数，避免反复创建中间 Map。
     */
    private static final class Candidate {
        private final String chunkId;
        private KnowledgeHitDto vectorHit;
        private VectorStoreInspector.KeywordChunk keywordChunk;
        private double rrfScore;

        Candidate(String chunkId) {
            this.chunkId = chunkId;
        }
    }

    /**
     * RRF 融合两个通道。
     *
     * <p>{@code score(chunk) = Σ 1/(k + rank)}，rank 从 1 开始。
     * 两个通道都命中的片段自然排在最前（两份贡献相加），这也是「可信度最高」的含义。
     */
    private List<KnowledgeHitDto> fuse(List<Document> vectorDocs, List<KeywordChannel> keywordChannels) {
        int k = properties.rrfK();
        double keywordWeight = properties.keywordChannelWeight();
        Map<String, Candidate> candidates = new LinkedHashMap<>();

        // 通道 1：向量。vectorDocs 已按相似度降序，索引即 rank。
        for (int index = 0; index < vectorDocs.size(); index++) {
            Document document = vectorDocs.get(index);
            String chunkId = document.getId();
            if (chunkId == null) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(chunkId, Candidate::new);
            candidate.vectorHit = toHit(document);
            candidate.rrfScore += 1.0 / (k + index + 1);
        }

        // 通道 2..N：每个 token 一路，按 IDF × 通道权重缩放。
        //
        // IDF 是关键：{@code io} 这种子串命中 51% 片段的 token，IDF 趋近 0，贡献可忽略；
        // {@code unknown} 这种只命中 3% 的，权重接近 1。这比「整体降低关键词权重」更精准 ——
        // 后者会连「本该被关键词救回」的查询一起压掉（实测权重 0.5 时 R@1 反而低 1.5pp）。
        //
        // 通道权重默认 1.0（同权），仅作为调参入口保留。
        for (KeywordChannel channel : keywordChannels) {
            double scale = keywordWeight * channel.idf();
            for (int index = 0; index < channel.chunks().size(); index++) {
                VectorStoreInspector.KeywordChunk chunk = channel.chunks().get(index);
                Candidate candidate = candidates.computeIfAbsent(chunk.id(), Candidate::new);
                candidate.keywordChunk = chunk;
                candidate.rrfScore += scale * (1.0 / (k + index + 1));
            }
        }

        // 按 RRF 分降序；同分时向量分高的优先（有语义依据比纯字符串匹配更可信）
        return candidates.values().stream()
                .sorted(Comparator
                        .comparingDouble((Candidate candidate) -> candidate.rrfScore).reversed()
                        .thenComparing(candidate -> {
                            Double score = candidate.vectorHit == null ? null : candidate.vectorHit.score();
                            return score == null ? -1.0 : score;
                        }, Comparator.reverseOrder()))
                .map(this::toFusedHit)
                .toList();
    }

    /** 把候选转成 DTO，按「哪些通道命中」标注来源 */
    private KnowledgeHitDto toFusedHit(Candidate candidate) {
        boolean byVector = candidate.vectorHit != null;
        boolean byKeyword = candidate.keywordChunk != null;
        MatchedBy matchedBy = byVector && byKeyword ? MatchedBy.BOTH
                : byVector ? MatchedBy.VECTOR : MatchedBy.KEYWORD;

        if (byVector) {
            KnowledgeHitDto hit = candidate.vectorHit;
            return new KnowledgeHitDto(hit.documentId(), hit.documentTitle(), hit.filename(),
                    hit.chunkIndex(), hit.content(), hit.score(), matchedBy);
        }
        // 仅关键词命中：元数据从 JSON 里取，score 为 null（没做过嵌入）
        return fromKeywordChunk(candidate.keywordChunk, matchedBy);
    }

    private KnowledgeHitDto fromKeywordChunk(VectorStoreInspector.KeywordChunk chunk,
                                             MatchedBy matchedBy) {
        JsonNode metadata = readMetadata(chunk.metadata());
        return new KnowledgeHitDto(
                parseUuid(metadata.path(KnowledgeMetadata.DOCUMENT_ID).asString(null)),
                metadata.path(KnowledgeMetadata.DOCUMENT_TITLE).asString(""),
                metadata.path(KnowledgeMetadata.FILENAME).asString(""),
                metadata.path(KnowledgeMetadata.CHUNK_INDEX).asInt(-1),
                chunk.content(),
                null,
                matchedBy);
    }

    private JsonNode readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (RuntimeException ex) {
            // 元数据坏了不该让整次检索失败：宁可少一条标题，也不返回 500
            log.warn("分块元数据解析失败，按空元数据处理", ex);
            return objectMapper.createObjectNode();
        }
    }

    // ------------------------------------------------------------------ 去重

    /**
     * 纯向量路径的去重：每篇文档保留相似度最高那条。
     */
    private List<KnowledgeHitDto> dedupeByDocument(List<Document> documents) {
        Map<UUID, KnowledgeHitDto> best = new LinkedHashMap<>();
        for (Document document : documents) {
            UUID documentId = parseUuid(document.getMetadata().get(KnowledgeMetadata.DOCUMENT_ID));
            if (documentId == null) {
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

    /**
     * 融合路径的去重：每篇文档保留 RRF 排名最高那条。
     *
     * <p>输入已按融合分降序，所以「首次出现」即为该文档最优的一条 ——
     * 用 {@link LinkedHashSet} 记录已见文档即可，不需要再比较分数。
     */
    private List<KnowledgeHitDto> dedupeFused(List<KnowledgeHitDto> fused) {
        Set<UUID> seen = new LinkedHashSet<>();
        List<KnowledgeHitDto> result = new ArrayList<>();
        for (KnowledgeHitDto hit : fused) {
            if (hit.documentId() == null) {
                // 元数据缺失的片段不去重，直接保留（宁可多一条，也不要静默丢掉）
                result.add(hit);
                continue;
            }
            if (seen.add(hit.documentId())) {
                result.add(hit);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 转换

    private KnowledgeHitDto toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new KnowledgeHitDto(
                parseUuid(metadata.get(KnowledgeMetadata.DOCUMENT_ID)),
                asString(metadata.get(KnowledgeMetadata.DOCUMENT_TITLE)),
                asString(metadata.get(KnowledgeMetadata.FILENAME)),
                asInt(metadata.get(KnowledgeMetadata.CHUNK_INDEX)),
                document.getText(),
                document.getScore(),
                MatchedBy.VECTOR);
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
