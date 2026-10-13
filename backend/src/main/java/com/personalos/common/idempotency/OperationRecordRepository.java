package com.personalos.common.idempotency;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 操作记录仓储。
 */
public interface OperationRecordRepository extends JpaRepository<OperationRecordEntity, Long> {

    Optional<OperationRecordEntity> findByIdempotencyKey(String idempotencyKey);

    List<OperationRecordEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId, Pageable pageable);

    List<OperationRecordEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
