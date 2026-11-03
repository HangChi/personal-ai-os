package com.personalos.knowledge.jobs;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.common.web.*;
import com.personalos.knowledge.config.KnowledgeProperties;
import com.personalos.knowledge.dto.KnowledgeDocumentDto;
import com.personalos.knowledge.service.*;
import com.personalos.knowledge.service.VectorStoreInspector.IndexedChunk;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.embedding.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 单实例数据库工作队列；准备阶段无事务，索引与成功回执在同一短事务提交。 */
@Service
public class KnowledgeIndexJobs {
    private final org.springframework.beans.factory.ObjectProvider<KnowledgeJobNotifier> notifier;
    private final KnowledgeJobRepository jobs;
    private final KnowledgeDocumentService documents;
    private final EmbeddingModel embeddings;
    private final VectorStoreInspector vectors;
    private final DocumentWriteLock locks;
    private final ModelConcurrencyGate gate;
    private final TokenBucketRateLimiter rate;
    private final JsonCodec json;
    private final JdbcTemplate jdbc;
    private final KnowledgeProperties properties;
    private final TransactionTemplate transaction;
    private final MeterRegistry metrics;
    private final boolean enabled;
    private com.personalos.common.config.RuntimeRole role = new com.personalos.common.config.RuntimeRole("combined");
    @org.springframework.beans.factory.annotation.Autowired
    void configureRole(com.personalos.common.config.RuntimeRole role) { this.role=role; }
    private final AtomicInteger active = new AtomicInteger();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "knowledge-job-lease"); thread.setDaemon(true); return thread;
    });
    public KnowledgeIndexJobs(KnowledgeJobRepository jobs, KnowledgeDocumentService documents, EmbeddingModel embeddings,
            VectorStoreInspector vectors, DocumentWriteLock locks, ModelConcurrencyGate gate, TokenBucketRateLimiter rate,
            JsonCodec json, JdbcTemplate jdbc, KnowledgeProperties properties, PlatformTransactionManager manager,
            MeterRegistry metrics, org.springframework.beans.factory.ObjectProvider<KnowledgeJobNotifier> notifier, @Value("${app.knowledge.jobs.enabled:true}") boolean enabled) {
        this.notifier=notifier;
        this.jobs=jobs; this.documents=documents; this.embeddings=embeddings; this.vectors=vectors; this.locks=locks;
        this.gate=gate; this.rate=rate; this.json=json; this.jdbc=jdbc; this.properties=properties; this.enabled=enabled;
        this.metrics=metrics;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        metrics.gauge("personalos.knowledge.jobs.active",active);
        metrics.gauge("personalos.knowledge.jobs.pending",jobs,repository -> repository.waitingCount());
    }
    public synchronized KnowledgeJobRepository.Job submit(UUID id, UUID target, String filename, String title, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > properties.maxFileSizeBytes())
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,"文件为空或超过知识库大小上限");
        if (filename == null || filename.length()>300 || (title!=null && title.length()>300))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"文件名和标题不能超过 300 字符");
        String digest=json.digestOf(List.of(target==null?"":target.toString(),filename,title==null?"":title,
                JsonCodec.sha256Hex(java.util.Base64.getEncoder().encodeToString(bytes))));
        var previous=jobs.find(id);
        if (previous.isPresent()) {
            if (!digest.equals(jobs.digest(id))) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            return previous.get();
        }
        if (jobs.waitingCount()>=20) throw new BusinessException(ErrorCode.RATE_LIMITED,"后台任务队列已满，请稍后再试");
        if (target!=null) documents.requireDocument(target);
        jobs.create(id,target==null?id:target,target!=null,filename,title,bytes,digest);
        notifier.ifAvailable(signal -> signal.submitted(id));
        return get(id);
    }
    public KnowledgeJobRepository.Job get(UUID id) { return jobs.find(id).orElseThrow(() -> new ResourceNotFoundException("后台任务不存在")); }
    public List<KnowledgeJobRepository.Job> recent() { return jobs.recent(); }

    @Scheduled(fixedDelayString="${app.knowledge.jobs.poll-ms:1000}",initialDelay=1000)
    public synchronized void dispatch() {
        if (!enabled || !role.allows("index") || active.get()>=(role.worker()?1:2)) return;
        active.incrementAndGet();
        try { workers.submit(() -> { try { runNext(); } finally { active.decrementAndGet(); } }); }
        catch (RejectedExecutionException ex) { active.decrementAndGet(); }
    }
    /** 独立于调度入口，便于在测试中同步驱动实际领取、崩溃恢复与提交。 */
    public boolean runNext() {
        if (!jobs.hasReady()) return false;
        var lease=gate.tryAcquire();
        if (lease==null) return false;
        try (lease) {
            if (!rate.tryAcquire()) return false;
            var claimed=jobs.claim();
            if (claimed.isEmpty()) return false;
            var claim=claimed.get();
            var renewal=heartbeat.scheduleAtFixedRate(() -> jobs.renew(claim),10,10,TimeUnit.SECONDS);
            long started=System.nanoTime();
            boolean locked=false;
            try {
                locks.acquire(claim.job().documentId()); locked=true;
                jobs.progress(claim,10,"解析文档");
                var upload=documents.prepareUpload(claim.job().filename(),claim.job().title(),claim.payload());
                var chunks=documents.chunksFor(upload,claim.job().documentId());
                if (chunks.isEmpty()) throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,"解析后没有可索引的内容");
                jobs.progress(claim,40,"计算嵌入");
                var output=embeddings.embed(chunks,EmbeddingOptions.builder().build(),new TokenCountBatchingStrategy());
                if (output.size()!=chunks.size()) throw new IllegalStateException("向量数量不匹配");
                List<IndexedChunk> prepared=new ArrayList<>();
                for (int i=0;i<chunks.size();i++) {
                    var chunk=chunks.get(i);
                    prepared.add(new IndexedChunk(UUID.fromString(chunk.getId()),chunk.getText(),json.toJson(chunk.getMetadata()),output.get(i)));
                }
                jobs.progress(claim,80,"保存索引");
                boolean committed=Boolean.TRUE.equals(transaction.execute(status -> {
                    if (!jobs.owns(claim)) return false; // 已失去租约的旧 worker 不能写入任何数据。
                    locks.fence(claim.job().documentId());
                    persist(claim,upload,prepared);
                    return true;
                }));
                metrics.counter("personalos.knowledge.jobs.attempts","outcome",committed?"success":"lease_lost").increment();
            } catch (RuntimeException ex) {
                boolean retry=claim.job().attempts()<3 && (!(ex instanceof BusinessException business)
                        || business.getErrorCode()==ErrorCode.BUSINESS_CONFLICT
                        || business.getErrorCode()==ErrorCode.AI_UPSTREAM_ERROR || business.getErrorCode()==ErrorCode.AI_TIMEOUT);
                jobs.fail(claim,retry,ex instanceof BusinessException business ? business.getErrorCode().getDefaultMessage() : "索引暂时失败，请检查模型连接或稍后重试");
                metrics.counter("personalos.knowledge.jobs.attempts","outcome",retry?"retry":"failed").increment();
            } finally {
                renewal.cancel(false);
                if (locked) locks.release(claim.job().documentId());
                metrics.timer("personalos.knowledge.jobs.duration").record(System.nanoTime()-started,TimeUnit.NANOSECONDS);
            }
            return true;
        }
    }
    private void persist(KnowledgeJobRepository.Claimed claim,KnowledgeDocumentService.PreparedUpload upload,List<IndexedChunk> chunks) {
        UUID id=claim.job().documentId();
        if (claim.job().replacing()) {
            int changed=jdbc.update("update knowledge_document set title=?,filename=?,content_type=?,size_bytes=?,char_count=?,chunk_count=?,updated_at=now() where id=?",
                    upload.title(),upload.filename(),upload.contentType(),upload.sizeBytes(),upload.charCount(),chunks.size(),id);
            if (changed!=1) throw new ResourceNotFoundException("原文档已被删除");
            vectors.deleteChunks(id);
        } else {
            jdbc.update("insert into knowledge_document(id,title,filename,content_type,size_bytes,char_count,chunk_count,created_at,updated_at) values (?,?,?,?,?,?,?,now(),now())",
                    id,upload.title(),upload.filename(),upload.contentType(),upload.sizeBytes(),upload.charCount(),chunks.size());
        }
        vectors.insertChunks(chunks);
        Instant created=jdbc.queryForObject("select created_at from knowledge_document where id=?",OffsetDateTime.class,id).toInstant();
        jobs.succeed(claim,json.toJson(new KnowledgeDocumentDto(id,upload.title(),upload.filename(),upload.contentType(),upload.sizeBytes(),upload.charCount(),chunks.size(),created)));
    }
    @Scheduled(cron="0 20 4 * * *") public void cleanup() { jobs.cleanup(); }
    @PreDestroy void close() { workers.shutdownNow(); heartbeat.shutdownNow(); }
}
