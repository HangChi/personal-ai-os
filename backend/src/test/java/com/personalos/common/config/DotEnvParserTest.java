package com.personalos.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DotEnvEnvironmentPostProcessor#parse(List)} 的单元测试。
 *
 * <p>只测解析这个纯函数：真正「注册到 Spring 环境」的效果由
 * {@code DotEnvEnvironmentPostProcessorTest} 之外的集成测试间接覆盖，
 * 而这里断言的是**字节到键值对**这一层，最容易出错也最容易测。
 */
@DisplayName(".env 解析")
class DotEnvParserTest {

    @Test
    void bindsMcpEnvironmentVariablesFromDotEnv(@TempDir Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".env"), """
                MCP_ENABLED=true
                MCP_SERVERS_FETCH_COMMAND=uvx
                MCP_SERVERS_FETCH_ARGS=mcp-server-fetch
                MCP_SERVERS_FETCH_AUTO_TOOLS=fetch
                MCP_SERVERS_FETCH_CONNECT_TIMEOUT=30s
                """);
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().remove("systemEnvironment");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                Map.of("app.dotenv.enabled", true)));
        new DotEnvEnvironmentPostProcessor(dir).postProcessEnvironment(env, null);
        var properties = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("mcp", com.personalos.mcp.config.McpProperties.class).get();
        assertThat(properties.enabled()).isTrue();
        assertThat(properties.servers()).containsKey("fetch");
        var fetch = properties.servers().get("fetch");
        assertThat(fetch.command()).isEqualTo("uvx");
        assertThat(fetch.args()).containsExactly("mcp-server-fetch");
        assertThat(fetch.autoTools()).containsExactly("fetch");
        assertThat(fetch.connectTimeout()).isEqualTo(java.time.Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("基本键值对与空行注释混排")
    void parsesSimplePairs() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "# 这是注释",
                "",
                "POSTGRES_DB=personal_ai_os",
                "POSTGRES_PORT=55432",
                "   ",
                "OPENAI_CHAT_MODEL=qwen3.8-flash"));

        assertThat(values)
                .containsEntry("POSTGRES_DB", "personal_ai_os")
                .containsEntry("POSTGRES_PORT", "55432")
                .containsEntry("OPENAI_CHAT_MODEL", "qwen3.8-flash")
                .hasSize(3);
    }

    @Test
    @DisplayName("值里的 = 与 : 原样保留（Base64 密钥常见）")
    void keepsEqualsAndColonInValue() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "DATABASE_URL=jdbc:postgresql://localhost:55432/db?a=b",
                "SIGNATURE=abc=="));

        assertThat(values)
                .containsEntry("DATABASE_URL", "jdbc:postgresql://localhost:55432/db?a=b")
                .containsEntry("SIGNATURE", "abc==");
    }

    @Test
    @DisplayName("行内 # 不剥离：密钥里可能有 #，误剥离会截断成错钥匙")
    void doesNotStripInlineComment() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of("KEY=abc#def"));

        // 这条断言是「不做行内注释剥离」这个决定的守卫。
        // 若有人为了「支持 KEY=value # 说明」而加剥离逻辑，这里会立刻红。
        assertThat(values).containsEntry("KEY", "abc#def");
    }

    @Test
    @DisplayName("成对引号被剥掉，不成对时原样保留")
    void unquotesOnlyPairedQuotes() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "DOUBLE=\"hello world\"",
                "SINGLE='hello world'",
                "UNPAIRED=\"oops",
                "MISMATCHED='a\""));

        assertThat(values)
                .containsEntry("DOUBLE", "hello world")
                .containsEntry("SINGLE", "hello world")
                .containsEntry("UNPAIRED", "\"oops")
                .containsEntry("MISMATCHED", "'a\"");
    }

    @Test
    @DisplayName("支持 export 前缀（从 shell 脚本复制来的常见写法）")
    void stripsExportPrefix() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "export OPENAI_API_KEY=sk-abc"));

        assertThat(values).containsEntry("OPENAI_API_KEY", "sk-abc");
    }

    @Test
    @DisplayName("无等号、空键、空行都被忽略，不抛异常")
    void skipsMalformedLines() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "JUST_A_WORD",
                "=novalue",
                "",
                "# 注释",
                "OK=1"));

        assertThat(values).containsOnlyKeys("OK");
    }

    @Test
    @DisplayName("重复键以后出现者为准，且保持插入顺序")
    void laterDuplicateWins() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of(
                "K=first",
                "OTHER=x",
                "K=second"));

        assertThat(values).containsEntry("K", "second");
        // LinkedHashMap：顺序稳定，便于日志与 /actuator/env 里对照
        assertThat(values.keySet()).containsExactly("K", "OTHER");
    }

    @Test
    @DisplayName("空值保留为空串，不丢弃该键（表示「明确要它为空」）")
    void keepsEmptyValue() {
        Map<String, Object> values = DotEnvEnvironmentPostProcessor.parse(List.of("OPENAI_API_KEY="));

        assertThat(values).containsEntry("OPENAI_API_KEY", "");
    }

    @Test
    void testEnvironmentMustNotReadLocalSecrets(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".env"), "AUTH_ENABLED=true\nPRIVATE_TEST_KEY=secret");
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                Map.of("app.dotenv.enabled", false)));
        new DotEnvEnvironmentPostProcessor(dir).postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().contains("dotenv-systemEnvironment")).isFalse();
        assertThat(env.getProperty("PRIVATE_TEST_KEY")).isNull();
    }

    @Test
    void localStartupKeepsFallbackAndEnvironmentPriority(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".env"), "LOCAL_TEST_KEY=from-file");
        java.nio.file.Path child = java.nio.file.Files.createDirectory(dir.resolve("backend"));
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",
                Map.of("app.dotenv.enabled", true, "LOCAL_TEST_KEY", "override")));
        new DotEnvEnvironmentPostProcessor(child).postProcessEnvironment(env, null);
        assertThat(env.getPropertySources().get("dotenv-systemEnvironment").getProperty("LOCAL_TEST_KEY")).isEqualTo("from-file");
        assertThat(env.getProperty("LOCAL_TEST_KEY")).isEqualTo("override");
        new DotEnvEnvironmentPostProcessor(dir.resolve("missing/deep/directory/more"))
                .postProcessEnvironment(new org.springframework.core.env.StandardEnvironment(), null);
    }
}
