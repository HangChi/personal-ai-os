package com.personalos.memory.tool;

import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.memory.dto.MemoryDto;
import com.personalos.memory.service.MemoryService;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 调用的长期记忆工具。
 *
 * <h2>只有「写」，没有「删」</h2>
 * 记忆会被注入到**之后每一次对话**，其影响面比单条任务大得多。
 * 因此模型只被授予「在用户明确要求时新增」这一项能力；修改与删除
 * 只在 {@code MemoryController}（面向人的界面）暴露（宪法原则 II 的延伸）。
 *
 * <h2>为什么不加人工确认</h2>
 * 阶段 2 的确认边界是「删除 / 批量 / 关键字段」——保存一条记忆三者都不是：
 * 不删数据、单条、且用户已经用自然语言说了「记住」。再弹一次确认属于重复询问
 * （specs/008-long-term-memory/research.md R-07）。
 */
@Component
public class MemoryTools {

    private final ToolExecutor toolExecutor;
    private final MemoryService memoryService;

    public MemoryTools(ToolExecutor toolExecutor, MemoryService memoryService) {
        this.toolExecutor = toolExecutor;
        this.memoryService = memoryService;
    }

    @Tool(name = "saveMemory",
            description = "把一条信息存入长期记忆，之后的所有会话都会遵守。"
                    + "**仅在用户明确要求长期记住某事时调用**（例如「记住：我的周报每周五交」"
                    + "「以后都按这个格式回复」）。不要自动记录普通对话内容，"
                    + "也不要把本轮任务的临时信息当成长期约束。")
    public String saveMemory(
            @ToolParam(description = "要长期记住的内容，1~2000 字，写成一句完整、自足的陈述") String content,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("content", content));

        return toolExecutor.write("saveMemory", arguments, toolContext, () -> {
            MemoryDto saved = memoryService.create(content);
            // 去重命中时 content 与本次提交相同，用户看到的仍是「已记住」，符合预期
            return ToolExecutionResult.ok("已记住：" + saved.content(), saved);
        });
    }
}
