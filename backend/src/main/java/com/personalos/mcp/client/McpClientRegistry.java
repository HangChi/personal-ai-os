package com.personalos.mcp.client;

import com.personalos.mcp.config.McpConfigValidator;
import com.personalos.mcp.config.McpProperties;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP server 生命周期注册表：按配置拉起 stdio server、连接、列举工具、统一关闭。
 *
 * <p><b>为什么自己建客户端而不走 Spring AI 的 {@code spring.ai.mcp.client.*} 自动配置</b>：
 * 我们的配置面是 {@code MCP_*}（项目自有约定），且需要逐个 server 的故障隔离与状态观测。
 * starter 的价值在于带来 SDK 与工具回调集成（Phase 2 用 {@code SyncMcpToolCallbackProvider}），
 * 客户端构建这一层自己掌握更直接。
 *
 * <p><b>生命周期</b>：{@link PostConstruct} 连接、{@link DisposableBean#destroy()} 关闭。
 * 连接放在 Bean 创建阶段，「启动失败」才真的是启动失败；关闭不依赖 server 礼貌——
 * {@code client.close()} 会关闭传输与子进程，不假设 server 会「检测到 stdin 关闭自退」（契约 L3）。
 *
 * <p><b>两类失败的区别对待</b>（spec research.md R-07，这是刻意设计）：
 * <ul>
 *   <li>配置缺 command → 校验直接抛异常，**应用启动失败**（用户错误，不降级）；</li>
 *   <li>单个 server 连不上 / 超时 → 标记不可用 + 告警日志，**应用照常启动**（可用性问题）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
public class McpClientRegistry implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(McpClientRegistry.class);

    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapperSupplier().get();

    private final McpProperties properties;
    private final McpConfigValidator configValidator;

    /** server 名 → 已连接客户端（按配置顺序，保证日志与状态可读） */
    private final Map<String, McpSyncClient> clients = new LinkedHashMap<>();

    /** server 名 → 状态（含不可用的） */
    private final Map<String, McpServerStatus> statuses = new LinkedHashMap<>();

    public McpClientRegistry(McpProperties properties, McpConfigValidator configValidator) {
        this.properties = properties;
        this.configValidator = configValidator;
    }

    /**
     * 连接全部已配置 server。
     *
     * <p>正常路径由 Spring 在 Bean 创建后调用；公开是便于测试与运维诊断
     * （断线场景下可手动重连）。配置校验失败直接抛出 → 应用启动失败；
     * 单个 server 连接失败只标记不可用，其余 server 与应用功能不受影响。
     */
    @PostConstruct
    public void connectAll() {
        configValidator.validate(properties);
        for (Map.Entry<String, McpProperties.ServerConfig> entry : properties.servers().entrySet()) {
            connect(entry.getKey(), entry.getValue());
        }
        long available = statuses.values().stream().filter(McpServerStatus::available).count();
        log.info("MCP 接入完成：共 {} 个 server，可用 {} 个", statuses.size(), available);
    }

    private void connect(String name, McpProperties.ServerConfig config) {
        try {
            ServerParameters parameters = ServerParameters.builder(config.command())
                    .args(config.args())
                    .env(config.env())
                    .build();
            Duration timeout = config.connectTimeout();
            McpSyncClient client = McpClient.sync(new StdioClientTransport(parameters, JSON_MAPPER))
                    // 2 参构造器自 mcp-core 2.0 起已过时（record 增加了 title/description/icons
                    // 等字段）。builder 的 (name, version) 就是原来的语义，其余字段留空。
                    .clientInfo(McpSchema.Implementation.builder("personal-ai-os", "0.1.0-SNAPSHOT").build())
                    .initializationTimeout(timeout)
                    .requestTimeout(timeout)
                    .build();

            // listTools 同时承担两件事：证明握手成功 + 拿到工具数。
            // 失败（含超时）走 catch → 该 server 不可用，其余继续。
            List<McpSchema.Tool> tools = client.listTools().tools();
            clients.put(name, client);
            statuses.put(name, McpServerStatus.available(name, tools.size()));
            log.info("MCP server 已连接：name={}, tools={}", name, tools.size());
        } catch (Exception ex) {
            clients.remove(name);
            statuses.put(name, McpServerStatus.unavailable(name, ex.getMessage()));
            log.warn("MCP server 连接失败，已标记不可用：name={}, reason={}", name, ex.getMessage());
        }
    }

    /** @return 全部 server 的状态快照（可用与不可用） */
    public Map<String, McpServerStatus> statuses() {
        return Map.copyOf(statuses);
    }

    /** @return 已连接的客户端（不含不可用的）；调用方不得关闭 */
    public Map<String, McpSyncClient> clients() {
        return Map.copyOf(clients);
    }

    /** @return 该 server 是否可用 */
    public boolean isAvailable(String name) {
        McpServerStatus status = statuses.get(name);
        return status != null && status.available();
    }

    @Override
    public void destroy() {
        for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
            try {
                entry.getValue().close();
                log.info("MCP server 连接已关闭：name={}", entry.getKey());
            } catch (Exception ex) {
                // 单个 server 关闭失败不能拦住其它 server 与应用关闭（契约 L4）
                log.warn("MCP server 关闭失败：name={}, reason={}", entry.getKey(), ex.getMessage());
            }
        }
        clients.clear();
    }
}
