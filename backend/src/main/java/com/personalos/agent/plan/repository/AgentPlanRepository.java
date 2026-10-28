package com.personalos.agent.plan.repository;

import com.personalos.agent.plan.entity.AgentPlanEntity;
import com.personalos.agent.plan.entity.PlanStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 计划仓储。 */
public interface AgentPlanRepository extends JpaRepository<AgentPlanEntity, UUID> {

    /** 当前进行中的计划（同一会话至多一个，由部分唯一索引保证） */
    Optional<AgentPlanEntity> findFirstByConversationIdAndStatusOrderByCreatedAtDesc(
            String conversationId, PlanStatus status);

    /** 最近一个计划（无论状态）：面板在计划结束后仍要显示结果 */
    Optional<AgentPlanEntity> findFirstByConversationIdOrderByCreatedAtDesc(String conversationId);
}
