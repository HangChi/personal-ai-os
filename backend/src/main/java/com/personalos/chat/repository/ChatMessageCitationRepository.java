package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatMessageCitationEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 消息引用快照仓储（阶段 19）。
 */
public interface ChatMessageCitationRepository extends JpaRepository<ChatMessageCitationEntity, Long> {

    /** 按消息 id 批量查询，正序（消息内按 citationIndex）。 */
    List<ChatMessageCitationEntity> findByMessageIdInOrderByMessageIdAscCitationIndexAsc(
            Collection<Long> messageIds);

    /** 按消息 id 批量计数（测试与排查用：避免全局 count 被其他用例的数据干扰）。 */
    long countByMessageIdIn(Collection<Long> messageIds);

    /** 按会话删除（会话删除路径用；消息删除由外键级联负责）。 */
    void deleteByMessageIdIn(Collection<Long> messageIds);
}
