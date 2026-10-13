package com.personalos.agent.tool;

import com.personalos.common.ai.ToolContributor;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把任务工具贡献给对话。
 *
 * <p>对话模块（{@code chat}）通过 {@link ToolContributor} 收集工具，因此它无需知道
 * 「任务」这个业务概念的存在 —— 阶段 3 的知识库工具会以完全相同的方式接入。
 */
@Component
public class TaskToolContributor implements ToolContributor {

    private final TaskTools taskTools;

    public TaskToolContributor(TaskTools taskTools) {
        this.taskTools = taskTools;
    }

    @Override
    public List<Object> toolObjects() {
        return List.of(taskTools);
    }
}
