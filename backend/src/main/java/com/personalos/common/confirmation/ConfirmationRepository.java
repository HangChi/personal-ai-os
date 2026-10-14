package com.personalos.common.confirmation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 待确认操作仓储。
 */
public interface ConfirmationRepository extends JpaRepository<ConfirmationEntity, UUID> {

    List<ConfirmationEntity> findByStatusOrderByCreatedAtDesc(ConfirmationStatus status);

    List<ConfirmationEntity> findByStatusAndConversationIdOrderByCreatedAtDesc(ConfirmationStatus status,
                                                                                  String conversationId);

    Optional<ConfirmationEntity> findByIdAndStatus(UUID id, ConfirmationStatus status);

    /**
     * 原子地把待确认记录从 {@code expected} 迁到 {@code newStatus}（compare-and-set）。
     *
     * <p>并发确认时只有一个事务能更新到行，另一个会得到 0 —— 这是「单次执行」的实现手段，
     * 无需为表增加版本列。注意：本方法必须在与「实际执行」**同一个事务**内调用，
     * 否则执行失败时无法整体回滚，会留下「已确认但没执行」的矛盾状态。
     *
     * @return 受影响行数；0 表示状态已不是 {@code expected}
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ConfirmationEntity c set c.status = :newStatus, c.resolvedAt = :resolvedAt "
            + "where c.id = :id and c.status = :expected")
    int compareAndSetStatus(@Param("id") UUID id,
                            @Param("expected") ConfirmationStatus expected,
                            @Param("newStatus") ConfirmationStatus newStatus,
                            @Param("resolvedAt") Instant resolvedAt);
}
