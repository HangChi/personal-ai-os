package com.personalos.common.ai;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.List;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 阻止框架把调用超限异常转成英文助手回复；沿应用的 SSE 错误路径结束本轮。 */
public class AgentToolCallingManager implements ToolCallingManager {
    private final ToolCallingManager delegate;

    public AgentToolCallingManager(ToolCallingManager delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse response) {
        try {
            return delegate.executeToolCalls(prompt, response);
        } catch (ToolCallLimitExceededException ex) {
            throw new BusinessException(ErrorCode.AGENT_EXECUTION_LIMIT);
        }
    }
}
