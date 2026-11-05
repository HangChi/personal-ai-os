package com.personalos.common.idempotency;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 操作记录仓储。
 *
 * <p>阶段 11 起追加 {@link JpaSpecificationExecutor}：审计列表要按 {@code source} /
 * {@code outcome} / {@code conversationId} 组合过滤，且**过滤必须发生在分页之前**。
 * 用 Specification 而不是 JPQL 的 {@code :param is null or ...}，与 {@code TaskRepository} 同一套写法
 * ——后者在 Postgres 上对可选枚举参数的类型推断很敏感，容易退化成 {@code text} 比较而静默不命中。
 */
public interface OperationRecordRepository extends JpaRepository<OperationRecordEntity, Long>,
        JpaSpecificationExecutor<OperationRecordEntity> {

    Optional<OperationRecordEntity> findByIdempotencyKey(String idempotencyKey);

    List<OperationRecordEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    List<OperationRecordEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Unresolved tool effects retain their ownership even after the audit retention cutoff. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            delete from OperationRecordEntity e where e.createdAt < ?1
            and not (e.source = com.personalos.common.idempotency.OperationSource.TOOL
                and e.outcome in (com.personalos.common.idempotency.IdempotencyOutcome.IN_PROGRESS,
                    com.personalos.common.idempotency.IdempotencyOutcome.FAILED))
            """)
    int deleteByCreatedAtBefore(java.time.Instant threshold);
}
