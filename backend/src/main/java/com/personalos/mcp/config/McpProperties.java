package com.personalos.mcp.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 接入配置（前缀 {@code mcp}）。
 *
 * <p>环境变量经 relaxed binding 绑定（与既有 {@code AI_*} / {@code SERVER_PORT} 风格一致）：
 * <pre>
 * MCP_ENABLED=true
 * MCP_SERVERS_FILESYSTEM_COMMAND=npx
 * MCP_SERVERS_FILESYSTEM_ARGS=-y,@modelcontextprotocol/server-filesystem,./mcp-sandbox
 * MCP_SERVERS_FILESYSTEM_ENV_API_KEY=xxx           # 透传给 server 子进程（密钥只从这里进）
 * MCP_SERVERS_FILESYSTEM_AUTO_TOOLS=read_file,list_directory
 * MCP_SERVERS_FILESYSTEM_CONNECT_TIMEOUT=10s
 * </pre>
 *
 * <p><b>变量名必须是 {@code MCP_SERVERS_&lt;名字&gt;_*}（复数）</b>：relaxed binding 把
 * {@code MCP_SERVERS_FILESYSTEM_COMMAND} 归一到 {@code mcp.servers.filesystem.command}，
 * 与字段名 {@code servers} 对齐；写成单数 {@code MCP_SERVER_*} 会归一到
 * {@code mcp.server.*}，**静默绑不上**（不报错，servers 永远是空 map）——
 * 这个坑由 {@code McpDegradedStartupIntegrationTest} 的断言守着。
 *
 * <p><b>没有单独的「启用列表」属性</b>：{@code servers} 映射的键就是 server 清单。
 * 少一个属性就少一种「声明了却没配置」的半成品状态（宪法原则 I）。
 *
 * @param enabled 总开关，默认 false——「装了 starter 但没配置」与「配置前」行为必须一致
 * @param servers server 名 → 配置
 */
@ConfigurationProperties(prefix = "mcp")
public record McpProperties(boolean enabled, Map<String, ServerConfig> servers) {

    /** 连接 / 调用超默认值：个人本机场景 10 秒足够，挂起的 server 不该拖住启动 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    public McpProperties {
        servers = servers == null ? Map.of() : Map.copyOf(servers);
    }

    /**
     * 单个 MCP server 的启动与策略配置。
     *
     * @param command        启动命令（如 {@code npx} / {@code java}）
     * @param args           参数列表（环境变量里的逗号分隔串由 Spring 转换）
     * @param env            透传给 server 子进程的环境变量（密钥只经这里，不进仓库与日志）
     * @param autoTools      策略为 AUTO（只读直行）的工具名清单；未列出的工具默认 CONFIRM
     * @param connectTimeout 连接与调用超时；非正值收敛为默认值
     */
    public record ServerConfig(
            String command,
            List<String> args,
            Map<String, String> env,
            Set<String> autoTools,
            Duration connectTimeout) {

        public ServerConfig {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            autoTools = autoTools == null ? Set.of() : Set.copyOf(autoTools);
            if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) {
                connectTimeout = DEFAULT_CONNECT_TIMEOUT;
            }
        }
    }
}
