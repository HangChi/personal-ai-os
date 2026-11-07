package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

class McpCallDiagnosticsTest {
    @Test
    void preservesUsefulRemoteReasonWithoutTreatingItAsInstructions() {
        var result = McpSchema.CallToolResult.builder().isError(true)
                .addTextContent("Access denied - path outside allowed directories: /docs not in /sandbox")
                .build();
        assertThat(McpCallDiagnostics.toolError("filesystem", "search_files", result).toString())
                .contains("TOOL_ERROR", "outside allowed directories", "/sandbox", "untrusted=true");
        assertThat(McpCallDiagnostics.toolError("fetch", "fetch", null).toString())
                .contains("未提供文本原因");
    }

    @Test
    void distinguishesWrappedProtocolRejectionFromTransportFailure() {
        var protocol = new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-32602, "Invalid arguments: path is required"));
        var wrapped = new IllegalStateException("wrapper", protocol);
        assertThat(McpCallDiagnostics.protocolError(wrapped)).isSameAs(protocol);
        assertThat(McpCallDiagnostics.callError("files", "search_files", wrapped).toString())
                .contains("PROTOCOL_ERROR", "-32602", "path is required");
        assertThat(McpCallDiagnostics.protocolError(new IllegalStateException("closed"))).isNull();
        assertThat(McpCallDiagnostics.callError("files", "search_files", new IllegalStateException("private-command-secret")).toString())
                .contains("CONNECTION_ERROR").doesNotContain("private-command-secret");
    }

    @Test
    void redactsCredentialsAndBoundsUntrustedText() {
        String raw = "Refused https://alice:private-credential@example.com/path?token=private-query "
                + "Authorization: Bearer private-bearer API_KEY=private-key password='private password' "
                + "{\"access_token\":\"private-json\"}\n\u001b[31m";
        assertThat(McpCallDiagnostics.sanitize(raw))
                .contains("example.com/path", "[redacted]")
                .doesNotContain("private-", "private password", "\n", "\u001b");
        assertThat(McpCallDiagnostics.sanitize("字".repeat(5000))).hasSize(1201).endsWith("…");
    }

 @Test void nullContentNonTextAndNestedDiagnosticsAreBounded(){
  var result=org.mockito.Mockito.mock(McpSchema.CallToolResult.class);
  assertThat(McpCallDiagnostics.toolError("s","t",result).toString()).contains("未提供文本原因");
  var nullText=org.mockito.Mockito.mock(McpSchema.TextContent.class);
  var image=org.mockito.Mockito.mock(McpSchema.ImageContent.class);
  org.mockito.Mockito.when(result.content()).thenReturn(java.util.List.of(nullText,image,new McpSchema.TextContent("first"),new McpSchema.TextContent("second")));
  assertThat(McpCallDiagnostics.toolError("s","t",result).toString()).contains("first second");
  org.mockito.Mockito.when(result.content()).thenReturn(java.util.List.of(new McpSchema.TextContent("😀".repeat(5000)),new McpSchema.TextContent("not included")));
  assertThat(McpCallDiagnostics.toolError("s","t",result).toString()).doesNotContain("not included");
  assertThat(McpCallDiagnostics.sanitize(null)).isEmpty();
  Throwable chain=new McpError(new McpSchema.JSONRPCResponse.JSONRPCError(-1,"hidden"));
  for(int i=0;i<18;i++)chain=new RuntimeException(chain);
  assertThat(McpCallDiagnostics.protocolError(chain)).isNull();
 }
}
