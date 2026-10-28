package com.personalos.agent.plan.repository;

import com.personalos.agent.plan.entity.AgentPlanStepEntity;
import com.personalos.agent.plan.entity.StepStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 计划步骤仓储。 */
public interface AgentPlanStepRepository extends JpaRepository<AgentPlanStepEntity, UUID> {

    /** 一个计划的全部步骤，按序号升序（渲染顺序即执行顺序） */
    List<AgentPlanStepEntity> findByPlanIdOrderBySeqAsc(UUID planId);

    /**
     * 当前正在执行的步骤。
     *
     * <p>「哪个步骤在跑」是每次工具调用都要问的问题（工具结果要归集到它），
     * 所以走一条带状态过滤的查询 + 部分索引，而不是把全部步骤读进内存再筛。
     */
    Optional<AgentPlanStepEntity> findFirstByPlanIdAndStatusOrderBySeqAsc(UUID planId, StepStatus status);

    /** 按序号取步骤（工具参数用 seq 引用，前端也用 seq） */
    Optional<AgentPlanStepEntity> findByPlanIdAndSeq(UUID planId, int seq);
}
