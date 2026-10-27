package com.personalos.common.idempotency;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;

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

    /** 删除早于某时刻的记录（保留期清理，审查 P2）。批量删除必须标 @Modifying */
    @Modifying
    int deleteByCreatedAtBefore(java.time.Instant threshold);
}
