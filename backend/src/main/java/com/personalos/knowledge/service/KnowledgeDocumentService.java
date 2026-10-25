package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.knowledge.KnowledgeMetadata;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.dto.KnowledgeDocumentQuery;
import com.personalos.knowledge.entity.KnowledgeDocumentEntity;
import com.personalos.knowledge.reader.DocumentReaderFactory;
import com.personalos.knowledge.repository.KnowledgeDocumentRepository;
import com.personalos.knowledge.splitter.MarkdownStructureTextSplitter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识文档的入库、列表与删除。
 *
 * <h2>为什么整个流程放在一个事务里</h2>
 * {@code PgVectorStore} 用的是同一个 {@code DataSource} 上的 {@code JdbcTemplate}，
 * 因此它会**加入**当前事务。于是「文档记录 + 向量」这两处写入要么一起成功、要么一起回滚，
 * 不会出现「文档记录写了但向量没写」这种半成品状态。
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private final KnowledgeDocumentRepository repository;
    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final VectorStoreInspector inspector;
    private final DocumentReaderFactory readerFactory;

    public KnowledgeDocumentService(KnowledgeDocumentRepository repository,
                                    VectorStore vectorStore,
                                    KnowledgeProperties properties,
                                    VectorStoreInspector inspector,
                                    DocumentReaderFactory readerFactory) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.inspector = inspector;
        this.readerFactory = readerFactory;
    }

    /**
     * 上传并建立索引：校验 → 解析 → 分块 → 向量化 → 落库。
     *
     * @param originalFilename 原始文件名（用于取扩展名与默认标题）
     * @param title            标题，可为空
     * @param bytes            文件字节
     * @return 入库后的文档视图
     */
    @Transactional
    public KnowledgeDocumentDto ingest(String originalFilename, String title, byte[] bytes) {
        String filename = sanitizeFilename(originalFilename);
        String extension = extensionOf(filename);
        if (!properties.isExtensionAllowed(extension)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, String.format(
                    "不支持的文件类型「%s」，当前仅支持：%s",
                    extension.isEmpty() ? "(无扩展名)" : extension,
                    String.join(" / ", properties.allowedExtensions())));
        }
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "文件内容为空");
        }
        if (bytes.length > properties.maxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, String.format(
                    "文件过大（%d 字节），上限为 %d 字节", bytes.length, properties.maxFileSizeBytes()));
        }

        Instant now = Instant.now();
        String contentType = contentTypeOf(extension);
        String documentTitle = (title == null || title.isBlank()) ? stripExtension(filename) : title.strip();
        if (documentTitle.length() > 300) {
            documentTitle = documentTitle.substring(0, 300);
        }

        // 先落文档记录以取得 id —— 向量元数据需要它，而元数据必须在写入向量前就准备好
        KnowledgeDocumentEntity document = repository.save(new KnowledgeDocumentEntity(
                documentTitle, filename, contentType, bytes.length, 0, now));

        List<Document> parsed = readerFactory
                .create(extension, contentType, bytes, baseMetadata(document))
                .get();

        // 字符数取「解析后的文本总长」，不是原始字节数：扫描版 PDF 的字节可能很大而文本为 0，
        // 用户关心的是「有多少字进了索引」
        int parsedChars = parsed.stream()
                .mapToInt(entry -> entry.getText().length())
                .sum();
        if (parsedChars < properties.minChunkSizeChars()) {
            // 过短文档源头拒收。评测（scripts/eval）中发现 29 / 92 字符的样例文档
            // 会因为向量信号过于"集中"而挤进检索前 5，把正确文档挤出榜 ——
            // 在检索端过滤要么漏放，要么误杀，不如一开始就不让它进库。
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "解析后内容仅 " + parsedChars + " 字符，少于 "
                            + properties.minChunkSizeChars() + "，无法形成有效分块");
        }

        List<Document> chunks = chunk(parsed, document.getId());
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "解析后没有可索引的内容");
        }

        try {
            vectorStore.add(chunks);
        } catch (RuntimeException ex) {
            // 维度不一致、模型不可达等问题只会在这一刻暴露，翻译成人能看懂的提示
            throw translateVectorFailure(ex);
        }

        // 字符数取「解析后的文本总长」，不是原始字节数：扫描版 PDF 的字节可能很大而文本为 0，
        // 用户关心的是「有多少字进了索引」
        document.updateStats(parsedChars, chunks.size(), Instant.now());
        KnowledgeDocumentEntity saved = repository.save(document);
        log.info("知识文档入库完成 id={} filename={} chars={} chunks={}",
                saved.getId(), filename, saved.getCharCount(), chunks.size());
        return toDto(saved);
    }

    /**
     * 文档列表，按上传时间倒序。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeDocumentDto> list() {
        return repository.findAllByOrderByCreatedAtDesc().stream().map(this::toDto).toList();
    }

    /**
     * 分页 + 关键词筛选的文档列表（阶段 16）。
     *
     * <p>关键词同时匹配**标题与文件名**：上传时若没有单独填标题，列表显示的就是文件名，
     * 只匹配标题会让「按文件名找」失效。
     *
     * <p>与 {@link #list()} 的分工：{@code list()} 给导出等需要全量的内部用途，本方法给界面。
     */
    @Transactional(readOnly = true)
    public PageDto<KnowledgeDocumentDto> search(KnowledgeDocumentQuery query) {
        Specification<KnowledgeDocumentEntity> spec = (root, criteriaQuery, builder) -> {
            if (query.keyword() == null) {
                return builder.conjunction();
            }
            String pattern = "%" + query.keyword().strip().toLowerCase(Locale.ROOT) + "%";
            return builder.or(
                    builder.like(builder.lower(root.get("title")), pattern),
                    builder.like(builder.lower(root.get("filename")), pattern));
        };

        PageRequest pageRequest = PageRequest.of(query.page(), query.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<KnowledgeDocumentEntity> page = repository.findAll(spec, pageRequest);
        List<KnowledgeDocumentDto> items = page.getContent().stream().map(this::toDto).toList();
        return new PageDto<>(items, query.page(), query.size(), page.getTotalElements());
    }

    /**
     * 删除文档并清理其全部向量（FR-005/FR-006）。
     *
     * <p><b>顺序很重要</b>：先删向量、再删文档记录。
     * 若反过来，一旦第二步之后失败，残留的向量将**再也无法按 document_id 定位清理**
     * （只剩一堆指向已不存在文档的向量）。当前顺序下最坏情况是「文档记录在但向量为空」，
     * 用户可以再删一次 —— 这是可恢复的失败（见 data-model.md）。
     *
     * @return 实际清理掉的向量条数
     */
    @Transactional
    public int delete(UUID documentId) {
        KnowledgeDocumentEntity document = repository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在：" + documentId));

        int before = inspector.countByDocumentId(documentId);

        // ① 先删向量
        vectorStore.delete(new FilterExpressionBuilder()
                .eq(KnowledgeMetadata.DOCUMENT_ID, documentId.toString())
                .build());

        // ② 再删文档记录
        repository.delete(document);

        // 兜底自检：过滤器若未生效，必须让整个事务回滚，而不是留下幽灵向量
        int after = inspector.countByDocumentId(documentId);
        if (after != 0) {
            throw new IllegalStateException(String.format(
                    "向量清理未完全生效：document_id=%s 仍残留 %d 条。已回滚本次删除。", documentId, after));
        }

        log.info("删除知识文档 id={} title={} 清理向量 {} 条", documentId, document.getTitle(), before);
        return before;
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentEntity requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在：" + documentId));
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> baseMetadata(KnowledgeDocumentEntity document) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(KnowledgeMetadata.DOCUMENT_ID, document.getId().toString());
        metadata.put(KnowledgeMetadata.DOCUMENT_TITLE, document.getTitle());
        metadata.put(KnowledgeMetadata.FILENAME, document.getFilename());
        return metadata;
    }

    /**
     * 分块并**重新编号** {@code chunk_index}。
     *
     * <p>重新编号而不是沿用切分器给出的顺序，是为了保证它是 0..n-1 连续无缺 ——
     * 引用里展示的「第几段」必须在任何切分参数下都说得通。
     */
    /**
     * 切分：结构感知优先，超长节才硬切兜底。
     *
     * <p>2026-10-25 从固定 token 切分（{@link TokenTextSplitter}）换成
     * {@link MarkdownStructureTextSplitter}：后者按标题分节、保留章节路径前缀，
     * 不会把一句话从中间劈开。换切的动机与效果见 {@code scripts/eval/README.md}。
     */
    private List<Document> chunk(List<Document> parsed, UUID documentId) {
        MarkdownStructureTextSplitter splitter = new MarkdownStructureTextSplitter(
                properties.chunkSizeChars(),
                properties.minChunkSizeChars(),
                properties.chunkOverlapChars());

        List<Document> split = splitter.transform(parsed);
        List<Document> chunks = new ArrayList<>(split.size());
        int index = 0;
        for (Document piece : split) {
            String text = piece.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(piece.getMetadata());
            metadata.put(KnowledgeMetadata.DOCUMENT_ID, documentId.toString());
            metadata.put(KnowledgeMetadata.CHUNK_INDEX, index);
            chunks.add(new Document(text, metadata));
            index++;
        }
        return chunks;
    }

    /**
     * 把向量写入阶段的异常翻译成可读的业务错误。
     *
     * <p>不直接透出原始报文（那是 SQL 与驱动细节），只保留「是哪一类问题」这一信息。
     */
    private BusinessException translateVectorFailure(RuntimeException ex) {
        String message = collectMessages(ex).toLowerCase(Locale.ROOT);
        if (message.contains("dimension") || message.contains("dims")) {
            return new BusinessException(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, String.format(
                    "嵌入模型输出维度与向量表结构不一致（表为 %d 维）。"
                            + "请把 app.knowledge.embedding-dimensions 与嵌入模型调整为一致，"
                            + "或新增迁移重建向量列。原始错误：%s",
                    properties.embeddingDimensions(), ex.getClass().getSimpleName()));
        }
        if (message.contains("connect") || message.contains("refused") || message.contains("timeout")
                || message.contains("unknownhost")) {
            return new BusinessException(ErrorCode.AI_UPSTREAM_ERROR,
                    "无法连接嵌入模型服务，请确认模型服务已启动（错误类型：" + ex.getClass().getSimpleName() + "）");
        }
        return new BusinessException(ErrorCode.INTERNAL_ERROR,
                "知识文档入库失败（错误类型：" + ex.getClass().getSimpleName() + "）");
    }

    private String collectMessages(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append(' ');
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return builder.toString();
    }

    private String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "untitled.txt";
        }
        // 去掉任何路径成分：上传方可能带上完整路径，我们只需要文件名
        String normalized = originalFilename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        return filename.isBlank() ? "untitled.txt" : filename;
    }

    /**
     * 扩展名 → MIME 类型。
     *
     * <p>图片的 MIME 不只是展示用：它会被传给视觉模型的 {@code Media}，
     * 写错会让模型收到一个「类型是 text/plain 的图片」，表现为识别不出任何内容。
     */
    private String contentTypeOf(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "text/plain";
        };
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return base.isBlank() ? filename : base;
    }

    private KnowledgeDocumentDto toDto(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentDto(entity.getId(), entity.getTitle(), entity.getFilename(),
                entity.getContentType(), entity.getSizeBytes(), entity.getCharCount(),
                entity.getChunkCount(), entity.getCreatedAt());
    }
}
