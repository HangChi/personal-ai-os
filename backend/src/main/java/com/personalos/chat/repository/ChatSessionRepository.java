package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatSessionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话仓储。
 */
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, String> {

    /**
     * @return 全部会话，按最近活动时间倒序（FR-002）
     */
    List<ChatSessionEntity> findAllByOrderByUpdatedAtDesc();
}
