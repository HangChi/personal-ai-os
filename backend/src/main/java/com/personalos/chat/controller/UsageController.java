package com.personalos.chat.controller;

import com.personalos.chat.dto.UsageStatsDto;
import com.personalos.chat.service.UsageStatsService;
import com.personalos.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量统计接口（清单第 7 项）。
 *
 * <p>路径选 {@code /api/v1/usage} 而不是 {@code /api/v1/chat/usage}：用量是
 * **账号级**统计，未来知识库嵌入、摘要、记忆抽取的消耗都会汇到同一张表。
 * 挂在 chat 下面会让「以后要加别的来源」变成一次 URL 迁移。
 *
 * <p>只读接口，无写操作，因此没有幂等/确认的需求。
 */
@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageStatsService usageStatsService;

    public UsageController(UsageStatsService usageStatsService) {
        this.usageStatsService = usageStatsService;
    }

    /**
     * 按日聚合的模型用量。
     *
     * @param days 覆盖天数（含今天），默认 30，服务层收敛到 1~365。
     *             这里不设 {@code @Min/@Max}：越界值收敛后返回真实生效的天数
     *             （响应里的 {@code days} 字段），比直接 400 更宽容 ——
     *             用户手改 URL 的 {@code ?days=9999} 不该得到一个错误页
     * @return 用量统计
     */
    @GetMapping
    public ApiResponse<UsageStatsDto> stats(@RequestParam(name = "days", required = false) Integer days) {
        return ApiResponse.ok(usageStatsService.stats(days));
    }
}
