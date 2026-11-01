package com.personalos.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpServerFeatures;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerAnnotationScannerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.annotations.McpServerSpecificationFactoryAutoConfiguration;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

/** Verify the actual server registration boundary with downstream client tools present. */
class PersonalMcpExposureTest {

    @Test
    void exposesOnlyPersonalReadOnlyTools() throws IOException {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        new ApplicationContextRunner()
                .withInitializer(context -> sources.forEach(source ->
                        context.getEnvironment().getPropertySources().addLast(source)))
                .withPropertyValues("spring.ai.mcp.server.enabled=true")
                .withConfiguration(AutoConfigurations.of(
                        McpServerAnnotationScannerAutoConfiguration.class,
                        McpServerSpecificationFactoryAutoConfiguration.class,
                        ToolCallbackConverterAutoConfiguration.class))
                .withBean(PersonalMcpTools.class, () -> new PersonalMcpTools(null, null, null, null))
                .withBean(ToolCallbackProvider.class, () -> () -> {
                    throw new AssertionError("MCP server must not collect chat/client tool callbacks");
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    List<McpServerFeatures.SyncToolSpecification> tools = context.getBeansOfType(List.class)
                            .values().stream().flatMap(List::stream)
                            .filter(McpServerFeatures.SyncToolSpecification.class::isInstance)
                            .map(McpServerFeatures.SyncToolSpecification.class::cast).toList();
                    assertThat(tools).extracting(spec -> spec.tool().name()).containsExactlyInAnyOrder(
                            "personal_list_tasks", "personal_search_knowledge",
                            "personal_list_calendar", "personal_list_memories");
                    assertThat(tools).allSatisfy(spec -> {
                        assertThat(spec.tool().annotations().readOnlyHint()).isTrue();
                        assertThat(spec.tool().annotations().destructiveHint()).isFalse();
                        assertThat(spec.tool().annotations().idempotentHint()).isTrue();
                        assertThat(spec.tool().annotations().openWorldHint()).isFalse();
                    });
                });
    }
}
