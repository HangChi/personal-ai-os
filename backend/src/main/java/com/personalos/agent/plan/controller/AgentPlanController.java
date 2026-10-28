package com.personalos.agent.plan.controller;

import com.personalos.agent.plan.dto.PlanDto;
import com.personalos.agent.plan.service.AgentPlanService;
import com.personalos.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话的当前计划（计划面板数据源）。
 *
 * <p>返回**最近一个计划**而不是「进行中的那个」：任务结束后面板仍要显示结果
 * （哪一步失败了、实际调用了什么），若只返回 ACTIVE，计划一完成面板就空了，
 * 用户会觉得「刚才的进度怎么没了」。
 *
 * <p>没有计划时返回 {@code data: null}，而不是 404：对一个「可能没有」的资源，
 * 用 404 会让前端把正常情况当错误处理（并弹出提示）。
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/plan")
public class AgentPlanController {

    private final AgentPlanService planService;

    public AgentPlanController(AgentPlanService planService) {
        this.planService = planService;
    }

    /** 最近一个执行计划；从未规划过时为 null */
    @GetMapping
    public ApiResponse<PlanDto> latest(@PathVariable String conversationId) {
        return ApiResponse.ok(planService.latestPlan(conversationId).orElse(null));
    }
}
