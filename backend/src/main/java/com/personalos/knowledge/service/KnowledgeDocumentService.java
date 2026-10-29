package com.personalos.knowledge.service;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.search.SearchPatterns;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Service;

// 文件名/标题/类型的规范化抽在同包的 DocumentFilenameSupport（纯函数、可单测）。
// 这里用静态导入保持调用点简洁：它们在本类里出现九次，写成类名限定会淹没主流程。
import static com.personalos.knowledge.service.DocumentFilenameSupport.contentTypeOf;
import static com.personalos.knowledge.service.DocumentFilenameSupport.extensionOf;
import static com.personalos.knowledge.service.DocumentFilenameSupport.sanitizeFilename;
import static com.personalos.knowledge.service.DocumentFilenameSupport.stripExtension;
import static com.personalos.knowledge.service.DocumentFilenameSupport.truncateTitle;

/**
 * 知识文档的入库、更新、列表与删除。
 *
 * <h2>事务边界为什么不是「整个流程一个事务」（2026-10-27 修正）</h2>
 * 早先的注释写着「一个事务保证文档记录与向量要么都成、要么都回滚」。原子性本身没错，
 * 但它换来了一个更糟的代价：**解析与向量化都在事务里**。向量化是每个分块一次嵌入 HTTP 调用，
 * 一本几十页的文档要几十秒 —— 这期间一直握着一条数据库连接（池只有 10 条、连接超时 5 秒），
 * 几个人同时上传就能让全站请求开始超时。
 *
 * <p>现在只让两处**真正的写入**进短事务，长耗时步骤在事务外：
 * <ol>
 *   <li>入库：落记录（取 id）→ 事务外解析+向量化 → 更新计数；向量化失败则删掉刚建的记录。</li>
 *   <li>替换：事务外解析 → 删旧向量+自检 → 事务外向量化 → 更新记录。</li>
 * </ol>
 * 代价是失败时可能留下「文档记录在、向量为空」，那是**可恢复**的（再传一次即可），
 * 已记录在 data-model.md。比起全站连接被占满，这个代价小得多。
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private final KnowledgeDocumentRepository repository;
    private final VectorStore vectorStore;
    private final KnowledgeProperties properties;
    private final VectorStoreInspector inspector;
    private final DocumentReaderFactory readerFactory;

    /** 短事务模板（默认传播行为）。长耗时步骤一律在它外面做 */
    private final TransactionTemplate transactionTemplate;

    /**
     * 替换 / 删除的进程内互斥（为什么不用数据库行锁，见 {@link DocumentWriteLock} 的类注释）。
     */
    private final DocumentWriteLock writeLock;

    public KnowledgeDocumentService(KnowledgeDocumentRepository repository,
                                    VectorStore vectorStore,
                                    KnowledgeProperties properties,
                                    VectorStoreInspector inspector,
                                    DocumentReaderFactory readerFactory,
                                    DocumentWriteLock writeLock,
                                    PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.inspector = inspector;
        this.readerFactory = readerFactory;
        this.writeLock = writeLock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 取事务模板。方法名直白，是为了在调用点一眼看出「这里开事务了」 */
    private TransactionTemplate requireTx() {
        return transactionTemplate;
    }

    /**
     * 上传并建立索引：校验 → 解析 → 分块 → 向量化 → 落库。
     *
     * <h2>为什么不再整个方法一个事务（2026-10-27）</h2>
     * 解析（可能是几十页 PDF）与向量化（**每个分块一次嵌入 HTTP 调用**）都可能耗时数秒到数十秒。
     * 这些工作若在事务里，会一直占着一条数据库连接：Hikari 池 10 条、连接超时 5 秒，
     * 几个人同时传文件就能让正常请求开始超时。现在只让「写一行记录」和「更新统计」进事务。
     *
     * <p>代价是需要显式补偿：向量化失败时要删掉刚建的文档记录，
     * 否则列表里会留下一个 0 分块的条目（看起来上传成功了，检索却永远查不到它）。
     *
     * @param originalFilename 原始文件名（用于取扩展名与默认标题）
     * @param title            标题，可为空
     * @param bytes            文件字节
     * @return 入库后的文档视图
     */
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
        documentTitle = truncateTitle(documentTitle);

        // 解析在事务外：此时还不知道 document_id，而 chunk() 会给每个分块补上它，
        // 所以读者只需拿到标题/文件名（它们决定引用里显示什么）
        List<Document> parsed = readerFactory
                .create(extension, contentType, bytes, metadataFor(null, documentTitle, filename))
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

        // 短事务 1：先落文档记录以取得 id —— 向量元数据与检索过滤都依赖它
        String finalTitle = documentTitle;
        KnowledgeDocumentEntity created = requireTx().execute(status -> repository.save(
                new KnowledgeDocumentEntity(finalTitle, filename, contentType, bytes.length, 0, now)));
        UUID documentId = created.getId();

        List<Document> chunks = chunk(parsed, documentId);
        if (chunks.isEmpty()) {
            discardQuietly(documentId);
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "解析后没有可索引的内容");
        }

        // 事务外：向量化（每个分块一次嵌入调用），失败则回滚掉刚建的记录
        try {
            vectorStore.add(chunks);
        } catch (RuntimeException ex) {
            discardQuietly(documentId);
            // 维度不一致、模型不可达等问题只会在这一刻暴露，翻译成人能看懂的提示
            throw translateVectorFailure(ex);
        }

        // 短事务 2：只写计数
        KnowledgeDocumentEntity saved = requireTx().execute(status -> {
            KnowledgeDocumentEntity document = repository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在：" + documentId));
            document.updateStats(parsedChars, chunks.size(), Instant.now());
            return repository.save(document);
        });
        log.info("知识文档入库完成 id={} filename={} chars={} chunks={}",
                saved.getId(), filename, saved.getCharCount(), chunks.size());
        return toDto(saved);
    }

    /**
     * 尽力删掉一个半成品文档记录（向量化失败时调用）。
     *
     * <p>失败只记 warn：真正的问题（模型不可达）马上会以业务异常抛给用户，
     * 这里再抛一个只会把原始原因盖掉。
     */
    private void discardQuietly(UUID documentId) {
        try {
            requireTx().executeWithoutResult(status -> repository.deleteById(documentId));
        } catch (RuntimeException ex) {
            log.warn("清理半成品文档失败，需人工清理 id={}", documentId, ex);
        }
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
            if (query.keyword() == null || query.keyword().isBlank()) {
                return builder.conjunction();
            }
            // 走 SearchPatterns：它负责小写化与 % _ \ 的转义。
            // 之前这里自己拼 "%" + keyword + "%"，搜 "100%" 会命中「100 之后任意字符」。
            String pattern = SearchPatterns.likePattern(query.keyword());
            return builder.or(
                    builder.like(builder.lower(root.get("title")), pattern, SearchPatterns.ESCAPE),
                    builder.like(builder.lower(root.get("filename")), pattern, SearchPatterns.ESCAPE));
        };

        /*
         * 页码溢出必须先拦（2026-10-29 实测又中招一次）。
         *
         * <p>{@code page=21474837&size=100} 交给 PageRequest.of 时，Spring Data
         * 用 int 算 offset → 抛 InvalidDataAccessApiUsageException → 落到全局兜底
         * 就是 **500 + 一条假堆栈**。页码是纯客户端输入，不该有能力制造 500。
         *
         * <p>同一轮已经在会话搜索与工具审计上修过，但那条防线是**逐个服务手写**的，
         * 这个接口当时没被点到名，于是漏了 —— 端到端日志里 10:35 又炸了一次。
         * 判据统一用 {@link PageDto#offsetOverflows}，别在这里另写一遍算术。
         *
         * <p>不能直接 return 空页了事：total 必须是真实总数，前端要靠它渲染分页器。
         * 所以只跳过内容查询、仍然走一次 count。
         */
        if (PageDto.offsetOverflows(query.page(), query.size())) {
            long total = repository.count(spec);
            return PageDto.ofPage(List.of(), query.page(), query.size(), total);
        }

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
        // 与 update 共用同一把锁，理由见 DocumentWriteLock 的类注释。
        // 注意：@Transactional 的提交发生在方法返回之后，所以释放锁与提交之间还有一个
        // 极短窗口（微秒级）。要彻底消除得把整个删除也搬进 TransactionTemplate，
        // 代价与收益不成比例 —— 单用户场景下第二次操作只会得到 404。
        writeLock.acquire(documentId);
        try {
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
        } finally {
            writeLock.release(documentId);
        }
    }

    /**
     * 用新文件替换已有文档的内容（「更新」而不是「删了重传」）。
     *
     * <h2>为什么 id 不变</h2>
     * 引用快照里存的是 {@code document_id}。如果做成「删除 + 新建」，历史回答里的引用
     * 会全部指向一个不存在的文档 —— 内容其实还在，只是断了链。
     *
     * <h2>顺序与 delete 一致：先删旧向量、再写新向量</h2>
     * 新旧内容的分块边界完全不同，两批向量混在一个 document_id 下，检索会返回
     * **已经不存在于文档里的段落** —— 那种回答看起来有出处，点开却对不上。
     *
     * <p>中途失败时最坏情况是「文档记录在、向量为空」，用户可以再更新一次；
     * 反过来（先写新向量再删旧）会留下两代向量混杂，不可恢复。
     *
     * <h2>标题跟着文件名走</h2>
     * 换了一个文件却还显示旧标题，用户会以为没生效。
     *
     * <h2>事务边界（2026-10-27）</h2>
     * 与 {@link #ingest} 同理：解析与向量化都在事务外，只让「删旧向量」与「更新记录」
     * 各占一个短事务。中间失败时文档记录仍是旧内容、向量为空 —— 可再更新一次。
     */
    public KnowledgeDocumentDto update(UUID documentId, String originalFilename, byte[] bytes) {
        // 替换与删除共用同一把锁：两者都要先清旧向量，
        // 并发执行会让两代向量混存（后果见 DocumentWriteLock 的类注释）。
        writeLock.acquire(documentId);
        try {
            return doUpdate(documentId, originalFilename, bytes);
        } finally {
            writeLock.release(documentId);
        }
    }

    /** 真正的替换实现：只允许由 {@link #update} 在持有互斥时调用。 */
    private KnowledgeDocumentDto doUpdate(UUID documentId, String originalFilename, byte[] bytes) {
        KnowledgeDocumentEntity document = requireDocument(documentId);

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

        String contentType = contentTypeOf(extension);
        // 标题与文件名必须**先算出新值**再构造向量元数据。
        // 这里曾经直接用 baseMetadata(document)：那时 document 还是更新前的实体，
        // 于是写进向量的是旧标题/旧文件名，检索引用和 ask 的来源名永远显示旧名字，
        // 而且没有任何入口能修正（除非再传一次同名文件）。
        String newTitle = truncateTitle(stripExtension(filename));
        Map<String, Object> metadata = metadataFor(documentId, newTitle, filename);
        // 先解析再删旧向量：解析失败（损坏文件、扫描版 PDF）时不该动到现有索引
        List<Document> parsed = readerFactory
                .create(extension, contentType, bytes, metadata)
                .get();
        int parsedChars = parsed.stream().mapToInt(entry -> entry.getText().length()).sum();
        if (parsedChars < properties.minChunkSizeChars()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "解析后内容仅 " + parsedChars + " 字符，少于 "
                            + properties.minChunkSizeChars() + "，无法形成有效分块");
        }
        List<Document> chunks = chunk(parsed, documentId);
        if (chunks.isEmpty()) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT, "解析后没有可索引的内容");
        }

        // 短事务 1：删旧向量 + 自检。**必须先删再写**：新旧分块边界完全不同，
        // 两批混在一个 document_id 下会让检索返回「文档里已经不存在的段落」。
        requireTx().executeWithoutResult(status -> {
            vectorStore.delete(new FilterExpressionBuilder()
                    .eq(KnowledgeMetadata.DOCUMENT_ID, documentId.toString())
                    .build());
            int leftover = inspector.countByDocumentId(documentId);
            if (leftover != 0) {
                throw new IllegalStateException(String.format(
                        "旧向量清理未完全生效：document_id=%s 仍残留 %d 条。已回滚本次更新。",
                        documentId, leftover));
            }
        });

        // 事务外：向量化。失败时最坏情况是「文档记录还是旧内容、向量为空」——
        // 用户可以再更新一次，属可恢复失败（见 data-model.md）。
        try {
            vectorStore.add(chunks);
        } catch (RuntimeException ex) {
            throw translateVectorFailure(ex);
        }

        // 短事务 2：用同一个 newTitle 落库。向量元数据与实体标题必须是同一个值，
        // 否则「引用显示的名字」与「文档列表里的名字」会对不上
        KnowledgeDocumentEntity saved = requireTx().execute(status -> {
            KnowledgeDocumentEntity current = repository.findById(documentId)
                    .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在：" + documentId));
            current.replaceContent(newTitle, filename, contentType, bytes.length,
                    parsedChars, chunks.size(), Instant.now());
            return repository.save(current);
        });
        log.info("知识文档已更新 id={} filename={} chars={} chunks={}",
                documentId, filename, parsedChars, chunks.size());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentEntity requireDocument(UUID documentId) {
        return repository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("知识文档不存在：" + documentId));
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, Object> baseMetadata(KnowledgeDocumentEntity document) {
        return metadataFor(document.getId(), document.getTitle(), document.getFilename());
    }

    /**
     * 显式给定标题与文件名的元数据构造。
     *
     * <p>文档替换必须用这个重载：调用时实体还没更新，从实体取值只能拿到旧名字，
     * 而元数据会被固化进向量，检索引用就永远显示旧标题。
     */
    private Map<String, Object> metadataFor(UUID documentId, String title, String filename) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        // 入库时还不知道 id（记录尚未创建），chunk() 会补上它；其余场景必须给值
        if (documentId != null) {
            metadata.put(KnowledgeMetadata.DOCUMENT_ID, documentId.toString());
        }
        metadata.put(KnowledgeMetadata.DOCUMENT_TITLE, title);
        metadata.put(KnowledgeMetadata.FILENAME, filename);
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

    private KnowledgeDocumentDto toDto(KnowledgeDocumentEntity entity) {
        return new KnowledgeDocumentDto(entity.getId(), entity.getTitle(), entity.getFilename(),
                entity.getContentType(), entity.getSizeBytes(), entity.getCharCount(),
                entity.getChunkCount(), entity.getCreatedAt());
    }
}
