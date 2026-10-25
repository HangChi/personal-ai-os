package com.personalos.mcp.status;

import com.personalos.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP 接入状态查询。
 *
 * <p>存在的理由：阶段 6 刻意让「server 连不上」只降级、不阻断启动（research R-07），
 * 代价是这个故障**只出现在启动那一瞬间的日志里**。不给它一个查询入口，
 * 降级就等于静默失效 —— 用户永远不知道自己接入的工具其实没连上。
 *
 * <p>只读，无副作用；不支持启停 server（生命周期由配置文件与进程决定，见 plan R-06）。
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpStatusController {

    private final McpStatusService statusService;

    public McpStatusController(McpStatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 当前接入的 server 状态。
     */
    @GetMapping("/servers")
    public ApiResponse<McpStatusDto> servers() {
        return ApiResponse.ok(statusService.status());
    }
}
