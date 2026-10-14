package com.personalos.calendar.controller;

import com.personalos.calendar.dto.ReminderDto;
import com.personalos.calendar.dto.ReminderScanResult;
import com.personalos.calendar.entity.ReminderStatus;
import com.personalos.calendar.service.ReminderScanner;
import com.personalos.calendar.service.ReminderService;
import com.personalos.common.api.ApiResponse;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提醒接口。
 *
 * <p>契约见 {@code specs/005-calendar-reminders/contracts/calendar-api.md}。
 */
@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    private final ReminderService reminderService;
    private final ReminderScanner scanner;
    private final Clock clock;

    public ReminderController(ReminderService reminderService, ReminderScanner scanner, Clock clock) {
        this.reminderService = reminderService;
        this.scanner = scanner;
        this.clock = clock;
    }

    /**
     * 查询提醒。
     *
     * @param dueOnly 默认 true：只返回已到点且未读的。设为 false 可提前看到「接下来会提醒什么」，
     *                也便于人工验证扫描是否已经把下一条物化出来
     */
    @GetMapping
    public ApiResponse<List<ReminderDto>> list(
            @RequestParam(required = false) ReminderStatus status,
            @RequestParam(required = false, defaultValue = "true") boolean dueOnly,
            @RequestParam(required = false, defaultValue = "0") int limit) {
        return ApiResponse.ok(reminderService.list(status, dueOnly, limit));
    }

    /**
     * 标记已读（幂等）。
     */
    @PostMapping("/{id}/read")
    public ApiResponse<ReminderDto> markRead(@PathVariable UUID id) {
        return ApiResponse.ok(reminderService.markRead(id));
    }

    /**
     * 手动触发一次扫描。
     *
     * <p>这是**排错设施**而不是业务接口：它让用户不必等到下一个调度周期就能验证扫描是否正常，
     * 也让「建了日程却看不到提醒」这类问题可以被快速二分（是扫描没跑，还是查询条件不对）。
     * 它不是「触发提醒」—— 提醒是否展示仍由 {@code remind_at <= now} 决定。
     */
    @PostMapping("/scan")
    public ApiResponse<ReminderScanResult> scan() {
        return ApiResponse.ok(scanner.scan(clock.instant()));
    }
}
