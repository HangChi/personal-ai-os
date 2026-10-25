package com.personalos.agent.audit;

import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.PageDto;
import com.personalos.common.idempotency.IdempotencyOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具执行日志查询（FR-017/018）。
 *
 * <p>对应「助手到底替我做了什么」这个自托管用户最关心的问题。
 * 日志同时包含被拒绝、被去重与失败的调用 —— 只记录成功会掩盖真实发生过的尝试。
 *
 * <p><b>阶段 11 变更</b>：
 * <ul>
 *   <li>{@code limit} 改成 {@code page + size}，响应改为 {@link PageDto}（原先既拿不到总数，
 *       又因「先分页后过滤」导致一页可能凑不满）；</li>
 *   <li>新增 {@code outcome} 筛选，用于定位「为什么助手说做了但其实没做」;</li>
 *   <li>{@code conversationId} 保留，供会话维度回溯。</li>
 * </ul>
 * 该接口此前**没有任何前端使用者**，改造无兼容负担。
 */
@RestController
@RequestMapping("/api/v1/agent/tool-invocations")
public class ToolInvocationController {

    private final ToolInvocationService service;

    public ToolInvocationController(ToolInvocationService service) {
        this.service = service;
    }

    /**
     * 查询工具执行日志，按发生时间倒序（同一毫秒按 id 倒序，保证翻页稳定）。
     *
     * @param conversationId 只看某个会话，可空
     * @param outcome        只看某种结果（SUCCESS / REQUIRES_CONFIRMATION / REJECTED / DUPLICATE / FAILED），可空
     * @param page           页码，从 0 开始
     * @param size           每页条数，默认 20、上限 100
     */
    @GetMapping
    public ApiResponse<PageDto<ToolInvocationDto>> list(
            @RequestParam(required = false) String conversationId,
            @RequestParam(required = false) IdempotencyOutcome outcome,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.search(
                new ToolInvocationQuery(conversationId, outcome, page, size)));
    }
}
