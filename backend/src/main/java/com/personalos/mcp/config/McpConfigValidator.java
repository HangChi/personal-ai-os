package com.personalos.mcp.config;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * MCP 配置校验：启动期拦住「配了 server 却没给启动命令」这类半成品配置。
 *
 * <p>为什么启动失败而不是跳过：配了 {@code MCP_ENABLED=true} 却漏了 command，
 * 说明用户**想**用这个 server。静默跳过会让用户以为「MCP 坏了」，
 * 而真正的原因只是一行配置的笔误——启动时指明缺哪项，排查成本最低。
 */
@Component
public class McpConfigValidator {

    /**
     * @throws BusinessException 某个 server 缺少启动命令时（错误信息含该补的环境变量名）
     */
    public void validate(McpProperties properties) {
        for (Map.Entry<String, McpProperties.ServerConfig> entry : properties.servers().entrySet()) {
            String name = entry.getKey();
            McpProperties.ServerConfig config = entry.getValue();
            if (config == null || config.command() == null || config.command().isBlank()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "MCP server「" + name + "」未配置启动命令：请设置 MCP_SERVERS_"
                                + name.toUpperCase(Locale.ROOT) + "_COMMAND");
            }
        }
    }
}
