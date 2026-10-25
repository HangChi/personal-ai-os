package com.personalos.mcp.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.client.McpServerStatus;
import com.personalos.mcp.config.McpProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link McpStatusService} 单元测试。
 *
 * <p>重点不是「能读到状态」，而是**未启用时也能安全回答**：
 * {@code McpClientRegistry} 是条件 Bean，关闭时不存在；
 * 状态查询端点如果直接依赖它，默认配置下应用会起不来。
 */
class McpStatusServiceTest {

    private static McpProperties properties(boolean enabled) {
        return new McpProperties(enabled, Map.of(
                "filesystem", new McpProperties.ServerConfig(
                        "npx", List.of("-y", "@modelcontextprotocol/server-filesystem"),
                        Map.of("MCP_TOKEN", "secret-value"),
                        Set.of("read_file", "list_directory"), null),
                "broken", new McpProperties.ServerConfig("nope", List.of(), Map.of(), Set.of(), null)));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpClientRegistry> providerOf(McpClientRegistry registry) {
        ObjectProvider<McpClientRegistry> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(registry);
        return provider;
    }

    @Test
    @DisplayName("未启用：返回 enabled=false 与空列表，且不需要注册表 Bean")
    void shouldReportDisabledWithoutRegistry() {
        McpStatusService service = new McpStatusService(properties(false), providerOf(null));

        McpStatusDto status = service.status();

        assertThat(status.enabled()).isFalse();
        assertThat(status.servers()).isEmpty();
    }

    @Test
    @DisplayName("已启用：逐个 server 输出可用性、工具数与只读清单（按名字排序）")
    void shouldReportServerStatus() {
        McpClientRegistry registry = mock(McpClientRegistry.class);
        given(registry.statuses()).willReturn(Map.of(
                "filesystem", McpServerStatus.available("filesystem", 14),
                "broken", McpServerStatus.unavailable("broken", "spawn nope ENOENT")));

        McpStatusDto status = new McpStatusService(properties(true), providerOf(registry)).status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.servers()).extracting(McpServerStatusDto::name)
                .containsExactly("broken", "filesystem");

        McpServerStatusDto broken = status.servers().get(0);
        assertThat(broken.available()).isFalse();
        assertThat(broken.failureReason()).isEqualTo("spawn nope ENOENT");
        assertThat(broken.toolCount()).isZero();

        McpServerStatusDto filesystem = status.servers().get(1);
        assertThat(filesystem.available()).isTrue();
        assertThat(filesystem.toolCount()).isEqualTo(14);
        // 只读清单按字典序，保证页面与断言都稳定
        assertThat(filesystem.autoTools()).containsExactly("list_directory", "read_file");
    }

    @Test
    @DisplayName("已启用但注册表缺失：如实报「状态未知」，不抛异常")
    void shouldReportUnknownWhenRegistryMissing() {
        McpStatusDto status = new McpStatusService(properties(true), providerOf(null)).status();

        assertThat(status.servers()).hasSize(2)
                .allSatisfy(server -> {
                    assertThat(server.available()).isFalse();
                    assertThat(server.failureReason()).contains("状态未知");
                });
    }

    @Test
    @DisplayName("响应不含 env / command / args —— 密钥与本机路径不出现在页面上")
    void shouldNotLeakSecrets() {
        McpClientRegistry registry = mock(McpClientRegistry.class);
        given(registry.statuses()).willReturn(Map.of(
                "filesystem", McpServerStatus.available("filesystem", 14)));

        McpStatusDto status = new McpStatusService(properties(true), providerOf(registry)).status();
        String json = new ObjectMapper().writeValueAsString(status);

        assertThat(json).doesNotContain("secret-value");
        assertThat(json).doesNotContain("\"env\"");
        assertThat(json).doesNotContain("\"command\"");
        assertThat(json).doesNotContain("\"args\"");
        assertThat(json).doesNotContain("server-filesystem");
    }
}
