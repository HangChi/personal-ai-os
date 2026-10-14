package com.personalos.mcp.confirmation;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.common.confirmation.ConfirmationService;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MCP 侧的「待确认事项」构造器。
 *
 * <p>职责边界与 {@code TaskConfirmationService} 完全一致：只负责把业务语义翻译成
 * 一段人类可读的 summary 与结构化的 payload；令牌、有效期、单次性、原子占位
 * 这些机制由 {@link ConfirmationService} 负责。
 */
@Service
public class McpConfirmationService {

    private final ConfirmationService confirmations;

    public McpConfirmationService(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    /**
     * 申请调用一个变更类 MCP 工具。
     *
     * @param server         server 名
     * @param tool           原始工具名
     * @param arguments      模型传入的参数（确认后原样回放给 server）
     * @param conversationId 发起会话，可空
     */
    @Transactional
    public ConfirmationDto requestToolCall(String server, String tool,
                                           Map<String, Object> arguments, String conversationId) {
        McpToolCall payload = new McpToolCall(server, tool, arguments);
        return confirmations.create(ConfirmationAction.MCP_TOOL_CALL,
                McpConfirmationSummary.summarize(payload), payload, 1, conversationId);
    }
}
