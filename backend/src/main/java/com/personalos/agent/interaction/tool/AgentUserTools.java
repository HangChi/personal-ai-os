package com.personalos.agent.interaction.tool;

import com.personalos.agent.interaction.dto.AgentQuestion;
import com.personalos.agent.interaction.service.AgentInteractionService;
import com.personalos.common.ai.ToolContributor;
import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.ai.tool.ToolExecutionResult;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentUserTools implements ToolContributor {
    private final AgentInteractionService interactions;
    private final ToolExecutor executor;
    public AgentUserTools(AgentInteractionService interactions, ToolExecutor executor) {
        this.interactions = interactions; this.executor = executor;
    }
    @Override public List<Object> toolObjects() { return List.of(this); }
    @Tool(name = "askUser", description = "关键意图或必要信息不明确且无法从已有上下文确定时，向用户提一个明确问题。"
            + "可给最多4个选项及简短影响说明，用户也可自由输入。调用后引擎暂停任务等待回答；不要自行替用户选择。"
            + "不用于授权删除或外部写操作，那些仍走人工确认。已回答的问题不要重复问。")
    public String askUser(@ToolParam(description = "需要用户澄清的一个问题") String question,
                         @ToolParam(required = false, description = "0到4个选项，label为选项名，description说明选择的影响") List<AgentQuestion.Option> options,
                         ToolContext context) {
        var arguments = ToolExecutor.arguments(args -> { args.put("question", question); args.put("options", options); });
        return executor.read("askUser", arguments, context, () -> {
            var result = interactions.ask(ToolExecutor.conversationId(context),
                    ToolExecutor.contextValue(context, ToolContextKeys.TURN_ID), question, options);
            return ToolExecutionResult.ok("ANSWERED".equals(result.status()) ? "用户已回答，请依据答案继续" : "问题已展示，等待用户回答", result);
        });
    }
}
