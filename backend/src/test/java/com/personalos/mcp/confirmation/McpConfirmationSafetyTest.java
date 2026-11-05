package com.personalos.mcp.confirmation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.tool.McpOperation;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class McpConfirmationSafetyTest {
    @Test
    void errorDoesNotClaimRollbackAndStableDecisionIdentityReachesRemote() {
        var client = mock(McpSyncClient.class);
        var registry = mock(McpClientRegistry.class);
        when(registry.clients()).thenReturn(Map.of("remote", client));
        var factory = new StaticListableBeanFactory();
        factory.addBean("registry", registry);
        var handler = new McpConfirmationHandler(factory.getBeanProvider(McpClientRegistry.class), new JsonCodec(new ObjectMapper()));
        String payload = "{\"server\":\"remote\",\"tool\":\"decrement\",\"arguments\":{\"quantity\":1}}";
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(McpSchema.CallToolResult.builder().isError(true).build());
        assertThatThrownBy(() -> handler.execute(ConfirmationAction.MCP_TOOL_CALL, payload, "confirmation:one"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("执行结果未确认").hasMessageNotContaining("目标未变更");
        var request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().meta()).containsEntry(McpOperation.META_KEY, "confirmation:one");
        assertThat(request.getValue().arguments()).containsEntry("quantity", 1);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenThrow(new IllegalStateException("connection reset"));
        assertThatThrownBy(() -> handler.execute(ConfirmationAction.MCP_TOOL_CALL, payload, "confirmation:two"))
                .hasMessageContaining("执行结果未确认").hasMessageNotContaining("重连后重试");
    }
}
