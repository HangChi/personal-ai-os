package com.personalos.mcp.client;

import com.personalos.common.config.AppProperties;
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
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * 应用自身信息，用于向 MCP server 声明 clientInfo。
     *
     * <p>刻意接 {@link AppProperties} 而不是写字面量：{@code app.version} 由 Maven 资源过滤
     * 从 pom 注入，手写的版本号在每次发版后都会与实际版本漂移，而且没有任何机制提醒你。
     */
    private final AppProperties appProperties;

    /** server 名 → 已连接客户端（按配置顺序，保证日志与状态可读） */
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    /** server 名 → 状态（含不可用的） */
    private final Map<String, McpServerStatus> statuses = new ConcurrentHashMap<>();

    public McpClientRegistry(McpProperties properties, McpConfigValidator configValidator,
                             AppProperties appProperties) {
        this.properties = properties;
        this.configValidator = configValidator;
        this.appProperties = appProperties;
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
        McpSyncClient client = null;
        try {
            ServerParameters parameters = ServerParameters.builder(config.command())
                    .args(config.args())
                    .env(config.env())
                    .build();
            Duration timeout = config.connectTimeout();
            client = McpClient.sync(new StdioClientTransport(parameters, JSON_MAPPER))
                    // 2 参构造器自 mcp-core 2.0 起已过时（record 增加了 title/description/icons
                    // 等字段）。builder 的 (name, version) 就是原来的语义，其余字段留空。
                    //
                    // 名字与版本取自 app.* 而非写死：版本由 pom 经资源过滤注入，
                    // 手写会随发版漂移（server 侧看到的永远是第一个版本）。
                    .clientInfo(McpSchema.Implementation
                            .builder(appProperties.name(), appProperties.version())
                            .build())
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
            if (client != null) {
                try { client.close(); } catch (RuntimeException closeError) { log.debug("清理 MCP 连接失败 name={}", name); }
            }
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

    /**
     * 调用失败回写状态（审查 P2）。
     *
     * <p>启动时 connect 成功后，子进程可能在运行期崩溃。若不回写，{@code isAvailable}
     * 仍读内存里那份「可用」快照，每轮工具调用都白等一次 10 秒超时。这里把失败
     * 记回状态，并顺手关掉那个已死的客户端——后续调用能立刻得到「不可用」的明确结果，
     * 而不是超时。
     *
     * @param name   server 名
     * @param reason 失败原因（用于状态页展示）
     */
    public void markUnavailable(String name, String reason) {
        markUnavailable(name, clients.get(name), reason);
    }

    /** 旧连接的迟到异常不得关闭已经重连的新客户端。 */
    public synchronized void markUnavailable(String name, McpSyncClient failedClient, String reason) {
        if (clients.get(name) != failedClient) return;
        statuses.put(name, McpServerStatus.unavailable(name, reason));
        McpSyncClient client = clients.remove(name);
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                log.debug("关闭已失效的 MCP client 失败：name={}, reason={}", name, ex.getMessage());
            }
        }
        log.warn("MCP server 调用失败，已标记不可用：name={}, reason={}", name, reason);
    }

    /**
     * 重连单个 server（供状态页手动触发，审查 P2）。
     *
     * <p>与 {@link #connect} 不同：这里在重连前先清掉旧状态，避免「旧失败原因」残留在状态页。
     * 失败时同样只标记不可用，不抛出。
     */
    public synchronized void reconnect(String name) {
        McpProperties.ServerConfig config = properties.servers().get(name);
        if (config == null) {
            log.warn("重连未知的 MCP server：name={}", name);
            return;
        }
        McpSyncClient previous = clients.remove(name);
        if (previous != null) {
            try { previous.close(); }
            catch (RuntimeException ex) { log.debug("关闭旧 MCP 连接失败 name={}", name); }
        }
        statuses.remove(name);
        connect(name, config);
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
