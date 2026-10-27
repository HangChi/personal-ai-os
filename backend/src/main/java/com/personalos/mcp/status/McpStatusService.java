package com.personalos.mcp.status;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.mcp.client.McpClientRegistry;
import com.personalos.mcp.client.McpServerStatus;
import com.personalos.mcp.config.McpProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * MCP 状态查询。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而不是直接注入 {@link McpClientRegistry}</b>：
 * 后者是 {@code @ConditionalOnProperty(mcp.enabled=true)}，关闭时这个 Bean 根本不存在。
 * 直接注入会让应用在默认配置（MCP 未启用）下**启动失败**——一个只读的状态查询端点，
 * 不该有这种副作用。取不到就当作「未启用」，与阶段 6 给 {@code ConfirmationActionHandler}
 * 定的规则一致：机制性 Bean 不能因为开关而消失。
 *
 * <p>本类与 Controller 都**无条件注册**：端点必须始终存在，
 * 否则前端无法区分「未启用」（404）与「路径写错」（404）。
 */
@Service
public class McpStatusService {

    private final McpProperties properties;
    private final ObjectProvider<McpClientRegistry> registryProvider;

    public McpStatusService(McpProperties properties, ObjectProvider<McpClientRegistry> registryProvider) {
        this.properties = properties;
        this.registryProvider = registryProvider;
    }

    /**
     * 读取当前 MCP 状态快照。
     *
     * @return 未启用时 {@code enabled=false, servers=[]}；启用时逐个 server 给出可用性与工具数
     */
    public McpStatusDto status() {
        if (!properties.enabled()) {
            return new McpStatusDto(false, List.of());
        }

        McpClientRegistry registry = registryProvider.getIfAvailable();
        // 状态快照是 Map.copyOf 包装的，不保证顺序；按名字排序让页面输出稳定可断言
        List<String> names = new ArrayList<>(properties.servers().keySet());
        names.sort(String::compareTo);

        List<McpServerStatusDto> servers = new ArrayList<>(names.size());
        for (String name : names) {
            McpProperties.ServerConfig config = properties.servers().get(name);
            List<String> autoTools = config == null
                    ? List.of()
                    : config.autoTools().stream().sorted().toList();

            McpServerStatus status = registry == null ? null : registry.statuses().get(name);
            if (status == null) {
                // 启用但注册表缺失（理论上只在开关切换的极端时序下出现）：如实说「未知」
                servers.add(new McpServerStatusDto(name, false, "状态未知（未建立连接）", 0, autoTools));
            } else {
                servers.add(new McpServerStatusDto(name, status.available(), status.failureReason(),
                        status.toolCount(), autoTools));
            }
        }
        return new McpStatusDto(true, servers);
    }

    /**
     * 重连指定 server（审查 P2）。
     *
     * <p>子进程运行期崩溃后，状态页可手动触发重连，而不必重启整个应用。
     * 失败同样只标记不可用、不抛出——重连是「再试一次」，不保证成功。
     */
    public void reconnect(String name) {
        McpClientRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            throw new BusinessException(ErrorCode.AI_NOT_CONFIGURED, "MCP 未启用，无法重连");
        }
        if (!properties.servers().containsKey(name)) {
            throw new ResourceNotFoundException("未知的 MCP server：" + name);
        }
        registry.reconnect(name);
    }
}
