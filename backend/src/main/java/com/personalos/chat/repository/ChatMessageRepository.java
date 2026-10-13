package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatMessageEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 消息仓储。
 *
 * <p>所有排序一律基于 {@code sequenceNo}，不依赖 {@code createdAt}（FR-018）。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /**
     * 按会话查询全部消息，正序。
     */
    List<ChatMessageEntity> findByConversationIdOrderBySequenceNoAsc(String conversationId);

    /**
     * @return 该会话当前的最大顺序号；无消息时为 0
     */
    @Query("select coalesce(max(m.sequenceNo), 0) from ChatMessageEntity m where m.conversationId = :conversationId")
    int findMaxSequenceNo(@Param("conversationId") String conversationId);

    /**
     * 取该会话**最近**的若干条消息（倒序取出，调用方负责反转成正序）。
     *
     * <p>用倒序 + 分页实现「取最后 N 条」，避免把整段历史读进内存。
     */
    @Query("select m from ChatMessageEntity m where m.conversationId = :conversationId order by m.sequenceNo desc")
    List<ChatMessageEntity> findRecentByConversationId(@Param("conversationId") String conversationId, Pageable pageable);

    long countByConversationId(String conversationId);

    /**
     * 一次性统计多个会话的消息数，避免在会话列表里逐条查询造成 N+1。
     *
     * @return 每项为 {@code [conversationId, count]}
     */
    @Query("select m.conversationId, count(m) from ChatMessageEntity m "
            + "where m.conversationId in :conversationIds group by m.conversationId")
    List<Object[]> countGroupedByConversationId(@Param("conversationIds") Collection<String> conversationIds);

    void deleteByConversationId(String conversationId);
}
