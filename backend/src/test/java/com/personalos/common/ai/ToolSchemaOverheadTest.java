package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personalos.support.StaticObjectProvider;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * {@link ToolSchemaOverhead} 的行为契约。
 *
 * <h2>为什么这个类值得测</h2>
 * 它只产出一个数字，但这个数字决定两件用户可见的事：①仪表盘的分子准不准；
 * ②预算的下限。写错的方式又都很安静 —— 少算了不会有异常，只会让界面继续显示
 * 「103 / 32K」这种看着很宽松、实际每次请求已送出 4K 的读数。
 */
@DisplayName("ToolSchemaOverhead 的固定开销测算")
class ToolSchemaOverheadTest {

    /** 一个假的工具对象：名字 4 字、描述 5 字、参数 schema 由框架生成 */
    static class DemoTools {

        @Tool(name = "echo", description = "回声工具")
        public String echo(String text) {
            return text;
        }
    }

    private static ToolSchemaOverhead overhead(List<Object> toolObjects, List<ToolCallback> callbacks) {
        ToolContributor contributor = () -> toolObjects;
        ToolCallbackProvider provider = () -> callbacks.toArray(new ToolCallback[0]);
        return new ToolSchemaOverhead(
                new StaticObjectProvider<>(List.of(contributor)),
                new StaticObjectProvider<>(List.of(provider)));
    }

    @Test
    @DisplayName("累加名字 + 描述 + 参数 schema 的字符数")
    void sumsDefinitionLengths() {
        int chars = overhead(List.of(new DemoTools()), List.of()).chars();

        // @Tool 的 definition 由框架生成：name=echo、description=回声工具，
        // schema 至少要含 text 这个属性名。用下界断言而不是精确值 ——
        // 精确值会随 Spring AI 版本的 schema 序列化细节漂移，那不是我们关心的。
        assertThat(chars).isGreaterThan("echo".length() + "回声工具".length());
    }

    @Test
    @DisplayName("已有的 ToolCallback 也被计入（MCP 走这条路）")
    void countsReadyCallbacks() {
        ToolDefinition definition = new DefaultToolDefinition("mcp__search", "搜索", "{\"type\":\"object\"}");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);

        int chars = overhead(List.of(), List.of(callback)).chars();

        assertThat(chars).isEqualTo("mcp__search".length() + "搜索".length() + "{\"type\":\"object\"}".length());
    }

    @Test
    @DisplayName("没有工具时返回 0，而不是抛异常")
    void emptyIsZero() {
        assertThat(overhead(List.of(), List.of()).chars()).isZero();
        assertThat(overhead(List.of(), List.of()).tokens()).isZero();
    }

    @Test
    @DisplayName("只算一次：工具集合在运行期不随模型切换而变")
    void computesOnlyOnce() {
        ToolContributor contributor = mock(ToolContributor.class);
        when(contributor.toolObjects()).thenReturn(List.of(new DemoTools()));
        ToolSchemaOverhead target = new ToolSchemaOverhead(
                new StaticObjectProvider<>(List.of(contributor)),
                new StaticObjectProvider<>(List.of()));

        target.chars();
        target.chars();

        // 第二次命中缓存：遍历工具并取 schema 不保证零成本（MCP 尤其如此），
        // 而它在每轮对话的装配路径上
        verify(contributor, times(1)).toolObjects();
    }

    @Test
    @DisplayName("工具集合读取失败时返回 0，不把对话打挂")
    void failureDegradesToZero() {
        ToolContributor brokenContributor = () -> {
            throw new IllegalStateException("MCP 客户端已断开");
        };
        ToolSchemaOverhead target = new ToolSchemaOverhead(
                new StaticObjectProvider<>(List.of(brokenContributor)),
                new StaticObjectProvider<>(List.of()));

        assertThat(target.chars()).isZero();
    }

    @Test
    @DisplayName("token 数与字符数同口径（1.6 字/token）")
    void tokensUseSameRatio() {
        ToolDefinition definition = new DefaultToolDefinition("t", "d", "x".repeat(1_600));
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);

        ToolSchemaOverhead target = overhead(List.of(), List.of(callback));

        assertThat(target.tokens()).isEqualTo(ContextBudgetService.toTokens(target.chars()));
    }

    @Test
    @DisplayName("schema 按压缩后计长：串外空白删掉、串内空白保留")
    void compactLengthKeepsStringWhitespace() {
        // pretty-print 的 schema（缩进 + 换行）与它的紧凑形式必须等长
        String pretty = """
                {
                  "type": "object",
                  "properties": {
                    "text": { "type": "string", "description": "要回声 的 文本" }
                  }
                }""";
        String compact = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\",\"description\":\"要回声 的 文本\"}}}";

        assertThat(ToolSchemaOverhead.compactLength(pretty)).isEqualTo(compact.length());
        // 紧凑形式本身没有串外空白：长度不变
        assertThat(ToolSchemaOverhead.compactLength(compact)).isEqualTo(compact.length());
        // 串内多一个空格，计数必须多 1（若被当空白删掉，差值会是 0）
        assertThat(ToolSchemaOverhead.compactLength("{\"a\":\"x  y\"}"))
                .isEqualTo(ToolSchemaOverhead.compactLength("{\"a\":\"x y\"}") + 1);
    }

    @Test
    @DisplayName("转义引号不会让扫描器误判字符串边界")
    void compactLengthHandlesEscapes() {
        // \" 是串内的转义引号；若把它当成闭合引号，后面的空白就会被当成「串外」删掉
        String json = "{\"a\":\"他说 \\\"你好\\\" 了\"}";

        assertThat(ToolSchemaOverhead.compactLength(json)).isEqualTo(json.length());
        assertThat(ToolSchemaOverhead.compactLength(null)).isZero();
        assertThat(ToolSchemaOverhead.compactLength("")).isZero();
    }
}
