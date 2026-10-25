package com.personalos.knowledge.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置（前缀 {@code app.knowledge}）。
 *
 * @param embeddingDimensions 嵌入维度，**必须**与 Flyway 迁移中 {@code vector_store.embedding} 一致
 * @param topK                默认检索条数
 * @param similarityThreshold 相似度阈值，低于该值的片段不进入上下文
 * @param maxFileSizeBytes    单文件大小上限
 * @param allowedExtensions   允许的扩展名
 * @param minChunkSizeChars   分块的最小字符数；也用于入库前拒收过短文档
 * @param chunkSizeChars      结构切分的目标块大小（字符）
 * @param chunkOverlapChars   相邻块的字符重叠；0 表示不重叠
 */
@ConfigurationProperties(prefix = "app.knowledge")
public record KnowledgeProperties(
        int embeddingDimensions,
        int topK,
        double similarityThreshold,
        long maxFileSizeBytes,
        List<String> allowedExtensions,
        int minChunkSizeChars,
        int chunkSizeChars,
        int chunkOverlapChars) {

    /** 检索条数硬上限：避免模型或调用方传一个巨大值把上下文撑爆 */
    public static final int MAX_TOP_K = 20;

    private static final int DEFAULT_DIMENSIONS = 1024;
    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_THRESHOLD = 0.3;
    private static final long DEFAULT_MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int DEFAULT_MIN_CHUNK_CHARS = 350;
    private static final int DEFAULT_CHUNK_SIZE_CHARS = 1200;
    private static final int DEFAULT_CHUNK_OVERLAP_CHARS = 0;

    public KnowledgeProperties {
        // 缺省或越界时归一为安全默认值，避免脏配置直接导致运行期怪异行为
        if (embeddingDimensions <= 0) {
            embeddingDimensions = DEFAULT_DIMENSIONS;
        }
        if (topK <= 0) {
            topK = DEFAULT_TOP_K;
        }
        if (topK > MAX_TOP_K) {
            topK = MAX_TOP_K;
        }
        if (similarityThreshold < 0d || similarityThreshold > 1d) {
            similarityThreshold = DEFAULT_THRESHOLD;
        }
        if (maxFileSizeBytes <= 0) {
            maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE;
        }
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            allowedExtensions = List.of("md", "markdown", "txt");
        }
        if (minChunkSizeChars <= 0) {
            minChunkSizeChars = DEFAULT_MIN_CHUNK_CHARS;
        }
        if (chunkSizeChars <= 0) {
            chunkSizeChars = DEFAULT_CHUNK_SIZE_CHARS;
        }
        if (chunkOverlapChars < 0) {
            chunkOverlapChars = DEFAULT_CHUNK_OVERLAP_CHARS;
        }
    }

    /**
     * 归一化检索条数：越界时收敛到合法区间，而不是让调用方拿到一个意外的结果集大小。
     */
    public int normalizeTopK(Integer requested) {
        if (requested == null || requested <= 0) {
            return topK;
        }
        return Math.min(requested, MAX_TOP_K);
    }

    /**
     * 扩展名是否被允许（大小写不敏感）。
     */
    public boolean isExtensionAllowed(String extension) {
        if (extension == null || extension.isBlank()) {
            return false;
        }
        String normalized = extension.strip().toLowerCase();
        return allowedExtensions.stream().anyMatch(allowed -> allowed.toLowerCase().equals(normalized));
    }
}
