package com.personalos.agent.plan.tool;

import com.personalos.common.ai.ToolContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把计划工具贡献给对话模块。
 *
 * <p>与任务/日程/知识库/记忆工具走同一个 SPI（{@link ToolContributor}）：
 * 对话模块只依赖 {@code common}，新增一类工具不需要改它。
 */
@Component
public class AgentPlanToolContributor implements ToolContributor {

    private final AgentPlanTools planTools;

    public AgentPlanToolContributor(AgentPlanTools planTools) {
        this.planTools = planTools;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(planTools);
    }
}
