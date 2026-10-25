package com.personalos.chat.repository;

import com.personalos.chat.entity.ChatSessionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

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
}
