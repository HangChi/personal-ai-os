package com.personalos.mcp.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.json.JsonCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link McpToolCallbackProvider} 的 schema 自检单元测试。
 *
 * <p>为什么要单独直接测这个方法：实测确认 Spring AI 的转换层会**归一化 schema**
 * （给缺 {@code type} 的声明补上 {@code "type":"object"}），所以「server 声明坏 schema」
 * 这条路径从集成测试走不到。自检因此退化为防御性代码——防御性代码更要直接测，
 * 否则它既不会被集成测试覆盖，也没人知道它到底还起不起作用。
 */
class McpToolCallbackProviderTest {

    private final JsonCodec jsonCodec = new JsonCodec(new ObjectMapper());

    /**
     * 直接实现接口而不是用 {@code ToolDefinition.builder()}：builder 会拒绝 null/空 schema
     * （实测报 {@code inputSchema cannot be null or empty}），而自检恰恰要覆盖这两种输入。
     * 用桩才能把「防御性分支」真的测到。
     */
    private static ToolDefinition definition(String inputSchema) {
        return new ToolDefinition() {
            @Override
            public String name() {
                return "t";
            }

            @Override
            public String description() {
                return "d";
            }

            @Override
            public String inputSchema() {
                return inputSchema;
            }
        };
    }

    @Test
    @DisplayName("合法 schema（type=object 且 properties 为对象）→ 可用")
    void validSchemaIsUsable() {
        assertThat(McpToolCallbackProvider.hasUsableSchema(
                definition("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"),
                jsonCodec)).isTrue();
        assertThat(McpToolCallbackProvider.hasUsableSchema(
                definition("{\"type\":\"object\",\"properties\":{}}"), jsonCodec)).isTrue();
    }

    @Test
    @DisplayName("type 不是 object → 不可用（数组等非对象入参不暴露给模型）")
    void nonObjectTypeIsRejected() {
        assertThat(McpToolCallbackProvider.hasUsableSchema(
                definition("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"), jsonCodec)).isFalse();
    }

    @Test
    @DisplayName("properties 缺失或不是对象 → 不可用")
    void missingPropertiesIsRejected() {
        assertThat(McpToolCallbackProvider.hasUsableSchema(
                definition("{\"type\":\"object\"}"), jsonCodec)).isFalse();
        assertThat(McpToolCallbackProvider.hasUsableSchema(
                definition("{\"type\":\"object\",\"properties\":\"oops\"}"), jsonCodec)).isFalse();
    }

    @Test
    @DisplayName("空白或损坏的 JSON → 不可用（不让解析异常冒到装配期）")
    void blankOrBrokenJsonIsRejected() {
        assertThat(McpToolCallbackProvider.hasUsableSchema(definition(null), jsonCodec)).isFalse();
        assertThat(McpToolCallbackProvider.hasUsableSchema(definition("  "), jsonCodec)).isFalse();
        assertThat(McpToolCallbackProvider.hasUsableSchema(definition("{not json"), jsonCodec)).isFalse();
    }
}
