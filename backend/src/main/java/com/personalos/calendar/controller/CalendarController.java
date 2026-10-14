package com.personalos.calendar.controller;

import com.personalos.calendar.dto.CalendarEventDeletedDto;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.dto.CalendarOccurrenceDto;
import com.personalos.calendar.dto.CreateEventRequest;
import com.personalos.calendar.dto.UpdateEventRequest;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.common.api.ApiResponse;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 日程接口。
 *
 * <p>契约见 {@code specs/005-calendar-reminders/contracts/calendar-api.md}。
 *
 * <p>本层的删除**不需要确认** —— 发起者就是人本身（与阶段 2 的任务删除一致）。
 * 由 Agent 发起的删除走确认流程，确认途径不在本控制器。
 */
@RestController
@RequestMapping("/api/v1/calendar/events")
public class CalendarController {

    private final CalendarEventService eventService;

    public CalendarController(CalendarEventService eventService) {
        this.eventService = eventService;
    }

    /**
     * 创建日程。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CalendarEventDto> create(@Valid @RequestBody CreateEventRequest request) {
        return ApiResponse.ok(eventService.create(request));
    }

    /**
     * 按区间查询，返回展开后的发生实例（分页）。
     *
     * <p>时间参数用字符串接收后自行解析，而不是直接声明 {@code Instant}：
     * 这样格式错误能给出一句「时间格式不正确」的中文提示，
     * 而不是被框架的类型转换异常带偏（那会让用户看到一句与业务无关的技术报错）。
     *
     * <p>2026-10-14 起 {@code limit} 改为 {@code page + size}，响应为
     * {@link PageDto}（含 total）——重复日程在大区间下的实例数常超上限，静默截断会翻不到后面的日期。
     */
    @GetMapping
    public ApiResponse<PageDto<CalendarOccurrenceDto>> search(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(eventService.search(
                parseInstant(from, "from"), parseInstant(to, "to"), page, size));
    }

    /**
     * 查询单个日程（原始定义，不展开重复）。
     */
    @GetMapping("/{id}")
    public ApiResponse<CalendarEventDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(eventService.get(id));
    }

    /**
     * 修改日程。
     */
    @PatchMapping("/{id}")
    public ApiResponse<CalendarEventDto> update(@PathVariable UUID id,
                                                @Valid @RequestBody UpdateEventRequest request) {
        return ApiResponse.ok(eventService.update(id, request));
    }

    /**
     * 删除日程，并一并删除它的提醒。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<CalendarEventDeletedDto> delete(@PathVariable UUID id) {
        int deletedReminders = eventService.delete(id);
        return ApiResponse.ok(new CalendarEventDeletedDto(true, deletedReminders));
    }

    private Instant parseInstant(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "参数 " + field + " 不是合法的时间，请使用 ISO-8601（如 2026-10-15T09:00:00Z）");
        }
    }
}
