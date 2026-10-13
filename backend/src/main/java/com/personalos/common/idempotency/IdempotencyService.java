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
import org.springframework.stereotype.Service;

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
            return replay(existing.get(), request);
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
        return new IdempotentResult(record.getResponsePayload(), record.getOutcome(), true);
    }

    private Integer elapsedMillis(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000L);
    }
}
