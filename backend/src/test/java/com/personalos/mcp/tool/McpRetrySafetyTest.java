package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.personalos.common.ai.ToolContextKeys;
import com.personalos.common.ai.tool.ToolCallRecorder;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.confirmation.McpConfirmationService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;

class McpRetrySafetyTest {
    @Test
    void autoCallWithSideEffectIsDurablyDeduplicatedIncludingReorderedArguments() {
        var repository = mock(OperationRecordRepository.class);
        var records = new HashMap<String, OperationRecordEntity>();
        when(repository.findByIdempotencyKey(any())).thenAnswer(invocation -> Optional.ofNullable(records.get(invocation.getArgument(0))));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            OperationRecordEntity row = invocation.getArgument(0);
            records.put(row.getIdempotencyKey(), row);
            return row;
        });
        var codec = new JsonCodec(new ObjectMapper());
        var executor = new ToolExecutor(new IdempotencyService(repository), codec,
                new StaticListableBeanFactory().getBeanProvider(ToolCallRecorder.class));
        var client = mock(McpSyncClient.class);
        var registry = mock(McpClientRegistry.class);
        when(registry.clients()).thenReturn(Map.of("remote", client));
        AtomicInteger quantity = new AtomicInteger(3);
        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            quantity.decrementAndGet();
            return McpSchema.CallToolResult.builder().addTextContent("done").build();
        });
        var callback = new McpToolCallback(mock(ToolCallback.class), "remote", "decrement", McpToolPolicy.AUTO,
                executor, mock(McpConfirmationService.class), codec, registry);
        var context = new ToolContext(Map.of(ToolContextKeys.CONVERSATION_ID, "c", ToolContextKeys.TURN_ID, "t"));
        assertThat(callback.call("{\"quantity\":1,\"item\":{\"z\":2,\"a\":1}}", context)).contains("OK");
        // No in-memory budget is needed to replay the durable receipt.
        assertThat(callback.call("{\"item\":{\"a\":1,\"z\":2},\"quantity\":1}", context)).contains("DUPLICATE");
        assertThat(quantity).hasValue(2);
        verify(client, times(1)).callTool(any(McpSchema.CallToolRequest.class));
        var request = ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client).callTool(request.capture());
        assertThat(request.getValue().meta().get(McpOperation.META_KEY)).isNotNull();

        when(client.callTool(any(McpSchema.CallToolRequest.class))).thenAnswer(invocation -> {
            quantity.decrementAndGet();
            return McpSchema.CallToolResult.builder().isError(true).addTextContent("failed after committing").build();
        });
        var next = new ToolContext(Map.of(ToolContextKeys.CONVERSATION_ID, "c", ToolContextKeys.TURN_ID, "next"));
        assertThat(callback.call("{}", next)).contains("执行结果未确认").doesNotContain("目标未变更");
        assertThat(callback.call("{}", next)).contains("REJECTED");
        assertThat(quantity).hasValue(1);
    }
}
