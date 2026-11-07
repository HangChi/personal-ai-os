package com.personalos.mcp.tool;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.personalos.common.ai.*;
import com.personalos.common.ai.tool.*;
import com.personalos.common.idempotency.*;
import com.personalos.common.json.JsonCodec;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.confirmation.McpConfirmationService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.ObjectMapper;
class McpCallbackInputBoundaryTest {
 @Test void unavailableEmptyArgumentsAndSourceRegistrarsUseCurrentConnection(){
  var repository=mock(OperationRecordRepository.class);when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());when(repository.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));
  var json=new JsonCodec(new ObjectMapper());var executor=new ToolExecutor(new IdempotencyService(repository),json,new StaticListableBeanFactory().getBeanProvider(ToolCallRecorder.class));
  var registry=mock(McpClientRegistry.class);var client=mock(McpSyncClient.class);
  var callback=new McpToolCallback(mock(ToolCallback.class),"remote","fetch",McpToolPolicy.AUTO,executor,mock(McpConfirmationService.class),json,registry);
  when(registry.clients()).thenReturn(Map.of());assertThat(callback.call("{}",null)).contains("不可用");
  when(registry.clients()).thenReturn(Map.of("remote",client));when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(McpSchema.CallToolResult.builder().addTextContent("plain result").build());
  for(String input:Arrays.asList(null," ","null","{bad","{\"items\":[1,{\"a\":true}]}"))assertThat(callback.call(input,null)).contains("OK");
  var context=new ToolContext(Map.of(ToolContextKeys.WEB_SOURCES,(WebSource.Registrar)sources->sources.stream().map(source->new WebSource.Numbered(1,source.title(),source.url(),source.content())).toList()));
  assertThat(callback.call("{}",context)).contains("OK");
  when(client.callTool(any(McpSchema.CallToolRequest.class))).thenReturn(McpSchema.CallToolResult.builder().addTextContent("Contents of https://example.test/page:\nEvidence text").build());
  assertThat(callback.call("{\"url\":\"https://example.test/page\"}",context)).contains("citations","不可信引用");
 }
}
