package com.personalos.common.idempotency;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 两层幂等的统一实现。
 *
 * <h2>为什么本类刻意不加 {@code @Transactional}</h2>
 * 它要做的第一件事是「占位插入」，而占位在并发下**必然会**在其中一个线程上抛出唯一键冲突。
 * 如果外层带事务，那次异常会把整个事务标记为 rollback-only，之后的查询与回填全部失败 ——
 * 这是 Spring 里非常经典、且症状（莫名其妙的事务已回滚）极具误导性的陷阱。
 *
 * <p>因此本类不自带事务，改为依赖仓储方法各自的短事务：占位失败只回滚占位本身，
 * 不影响后续步骤；被调用的业务动作也各自拥有自己的事务边界。
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    /**
     * IN_PROGRESS 残留的废弃阈值（审查 P2）。
     *
     * <p>占位（IN_PROGRESS）与回填是两个独立事务，进程若在中间崩溃，记录会永久停在
     * IN_PROGRESS —— 此后同一幂等键的所有请求都命中它、被当成「处理中」而拿不到结果。
     * 超过这个时长仍停在 IN_PROGRESS，就视为崩溃残留，删除后允许重新执行。
     * 5 分钟远超任何一次正常执行（最慢的是模型调用，也远不到 5 分钟）。
     */
    static final long IN_PROGRESS_STALE_SECONDS = 300;

    /** 幂等记录的保留期：审计价值会随时间衰减，无限堆积会拖慢按键查询 */
    private static final int RETENTION_DAYS = 30;

    private final OperationRecordRepository repository;

    public IdempotencyService(OperationRecordRepository repository) {
        this.repository = repository;
    }

    /**
     * 幂等执行一个操作。
     *
     * <p>流程：查已有记录 → 命中则回放（或摘要不一致则报冲突）→ 否则占位插入 →
     * 执行动作 → 回填结果。
     *
     * @param request 幂等描述
     * @param action  真正要执行的动作，返回响应文本与最终状态
     * @return 执行结果；命中幂等时 {@code replayed = true} 且响应与首次一致
     * @throws BusinessException 同一幂等键对应了不同的请求内容（code 40001）
     */
    public IdempotentResult execute(IdempotencyRequest request, Supplier<IdempotentResult> action) {
        Optional<OperationRecordEntity> existing = repository.findByIdempotencyKey(request.key());
        if (existing.isPresent()) {
            OperationRecordEntity record = existing.get();
            // 崩溃残留：占位后进程死掉，记录永久停在 IN_PROGRESS。超阈值则废弃重来，
            // 否则这个键永远「正在处理中」，既不能重试也不能复用（审查 P2）
            if (record.getOutcome() == IdempotencyOutcome.IN_PROGRESS && isStale(record)) {
                log.warn("清理崩溃残留的 IN_PROGRESS 幂等记录：key={} createdAt={}",
                        record.getIdempotencyKey(), record.getCreatedAt());
                repository.delete(record);
            } else {
                return replay(record, request);
            }
        }

        OperationRecordEntity placeholder = new OperationRecordEntity(request.key(), request.source(),
                request.operation(), request.digest(), request.conversationId(), request.turnId(), Instant.now());
        try {
            // 独立短事务：占位成功即代表「本次操作的所有权」到手
            repository.saveAndFlush(placeholder);
        } catch (DataIntegrityViolationException ex) {
            log.debug("幂等键已被其它请求占位，转为回放：key={}", request.key());
            OperationRecordEntity winner = repository.findByIdempotencyKey(request.key()).orElseThrow(() -> ex);
            return replay(winner, request);
        }

        long startNanos = System.nanoTime();
        IdempotentResult result;
        try {
            result = action.get();
        } catch (RuntimeException ex) {
            placeholder.complete(IdempotencyOutcome.FAILED, null, elapsedMillis(startNanos));
            repository.save(placeholder);
            throw ex;
        }

        placeholder.complete(result.outcome(), result.responsePayload(), elapsedMillis(startNanos));
        repository.save(placeholder);
        return result;
    }

    /**
     * 生成工具层幂等键。
     *
     * <p>键的构成包含**轮次标识**，因此幂等范围被刻意收窄到「同一轮对话内的同一操作」：
     * 跨轮次的相同调用会被正常执行 —— 用户在同一分钟内真诚地建两个同名任务是合法的，
     * 若被去重就是 bug（见 research.md R-04）。
     */
    public static String toolKey(String conversationId, String turnId, String toolName, String digest) {
        return JsonCodec.sha256Hex(conversationId + "|" + turnId + "|" + toolName + "|" + digest);
    }

    private IdempotentResult replay(OperationRecordEntity record, IdempotencyRequest request) {
        if (!record.getRequestDigest().equals(request.digest())) {
            // 同键不同内容：必须报错，绝不能静默执行成另一件事
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "该幂等键已用于内容不同的请求，请更换幂等键：" + request.key());
        }
        if (record.getOutcome() == IdempotencyOutcome.IN_PROGRESS) {
            // 并发窗口内：另一个请求正在执行同一操作
            log.debug("命中进行中的幂等记录：key={}", request.key());
            return IdempotentResult.of(record.getResponsePayload(), IdempotencyOutcome.DUPLICATE);
        }
        if (record.getOutcome() == IdempotencyOutcome.FAILED) {
            // 之前执行失败：responsePayload 是 null，原样回放会让调用方拿到「成功但空响应」，
            // 分不清是失败还是空结果。改为明确报错，用户换一个幂等键即可重试（审查 P2）
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT,
                    "该操作之前执行失败，请更换幂等键后重试：" + request.key());
        }
        return new IdempotentResult(record.getResponsePayload(), record.getOutcome(), true);
    }

    /**
     * 定期清理过期幂等记录（审查 P2）。
     *
     * <p>操作记录承担审计，但「几个月前建了哪个任务」的审计价值已归零，而无限堆积
     * 会让按键查询（唯一索引）变慢。保留 30 天，之后可安全删除。
     */
    @Scheduled(cron = "0 30 4 * * *")
    @Transactional
    public void cleanupExpiredRecords() {
        Instant threshold = Instant.now().minusSeconds((long) RETENTION_DAYS * 24 * 3600);
        int deleted = repository.deleteByCreatedAtBefore(threshold);
        if (deleted > 0) {
            log.info("清理过期幂等记录 {} 条（早于 {}）", deleted, threshold);
        }
    }

    /** 崩溃残留判定：IN_PROGRESS 且超过废弃阈值 */
    private boolean isStale(OperationRecordEntity record) {
        return record.getCreatedAt().isBefore(Instant.now().minusSeconds(IN_PROGRESS_STALE_SECONDS));
    }

    private Integer elapsedMillis(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
