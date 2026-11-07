package com.personalos.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.personalos.common.config.AppProperties;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.config.McpConfigValidator;
import com.personalos.mcp.config.McpProperties;
import io.modelcontextprotocol.client.McpSyncClient;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class McpRegistryLifecycleBoundaryTest {
    @Test
    @SuppressWarnings("unchecked")
    void unknownAndAlreadyDisconnectedServersRemainUnavailable() {
        var registry = new McpClientRegistry(new McpProperties(true, Map.of()),
                new McpConfigValidator(), new AppProperties("offline-test", "test"));
        registry.connectAll();
        assertThat(registry.isAvailable("unknown")).isFalse();
        registry.reconnect("unknown");
        registry.markUnavailable("unknown", "not connected");
        assertThat(registry.isAvailable("unknown")).isFalse();

        Map<String, McpSyncClient> clients = (Map<String, McpSyncClient>) ReflectionTestUtils.getField(registry, "clients");
        var broken = mock(McpSyncClient.class);
        doThrow(new IllegalStateException("transport closed")).when(broken).close();
        clients.put("broken", broken);
        registry.markUnavailable("broken", broken, "disconnected");
        registry.markUnavailable("broken", broken, "late failure");
        verify(broken).close();
        assertThat(registry.clients()).isEmpty();
        assertThat(registry.statuses().get("broken").failureReason()).isEqualTo("disconnected");
        registry.destroy();
    }
}
