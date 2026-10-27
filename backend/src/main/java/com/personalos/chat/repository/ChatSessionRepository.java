package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatSessionEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 会话仓储。
 *
 * <p>阶段 16 起追加 {@link JpaSpecificationExecutor}：会话列表要按标题关键词筛选 + 分页，
 * 且筛选必须发生在分页之前（与任务、知识库同一套写法）。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String>,
        JpaSpecificationExecutor<ChatSessionEntity> {

    /**
     * @return 全部会话，按最近活动时间倒序（FR-002）
     */
    List<ChatSessionEntity> findAllByOrderByUpdatedAtDesc();

    /**
     * 悲观锁地读会话行（审查 #13）。
     *
     * <p>{@code JpaChatMemory.add} 用它把「同会话的并发写」串行化：先锁住会话行，
     * 再 {@code findMaxSequenceNo + saveAll}，两个并发请求就只会一个接一个拿到
     * 递增的顺序号，而不是各自读到同一个 max 后撞 {@code UNIQUE(conversation_id, sequence_no)}。
     *
     * <p>锁的是会话行而不是消息行：会话只有一行，锁它不会放大锁竞争；
     * 且并发写天然按会话隔离，锁别的会话不影响彼此。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ChatSessionEntity s where s.conversationId = :id")
    Optional<ChatSessionEntity> findByIdForUpdate(@Param("id") String conversationId);
}
