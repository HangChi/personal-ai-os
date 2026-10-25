package com.personalos.calendar.tool;

import com.personalos.calendar.confirmation.CalendarConfirmationService;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.dto.CalendarOccurrenceDto;
import com.personalos.calendar.dto.CreateEventRequest;
import com.personalos.calendar.dto.ReminderDto;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.calendar.service.ReminderService;
import com.personalos.common.ai.tool.ToolArguments;
import com.personalos.common.ai.tool.ToolExecutionResult;
import com.personalos.common.ai.tool.ToolExecutor;
import com.personalos.common.confirmation.ConfirmationDto;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 供 Agent 调用的日程与提醒工具。
 *
 * <p>其中 {@code deleteCalendarEvent} 只**申请**执行：它创建一条待人类确认的记录，
 * 返回 {@code REQUIRES_CONFIRMATION}。真正执行它的是面向人类的确认接口 ——
 * 工具集合里没有任何东西能完成确认（阶段 2 建立的结构性防线，本阶段沿用）。
 */
@Component
public class CalendarTools {

    /** 单次批量操作上限，与任务侧保持一致 */
    private static final int MAX_BULK_SIZE = 50;

    private final CalendarEventService eventService;
    private final ReminderService reminderService;
    private final CalendarConfirmationService confirmationService;
    private final ToolExecutor toolExecutor;

    public CalendarTools(CalendarEventService eventService,
                         ReminderService reminderService,
                         CalendarConfirmationService confirmationService,
                         ToolExecutor toolExecutor) {
        this.eventService = eventService;
        this.reminderService = reminderService;
        this.confirmationService = confirmationService;
        this.toolExecutor = toolExecutor;
    }

    // ------------------------------------------------------------------ 创建

    @Tool(name = "createCalendarEvent",
            description = "创建一个日程（会议、约会、待办时间段等）。"
                    + "当用户说「记一下明天下午三点开会」「每周一早上九点站会」时调用。")
    public String createCalendarEvent(
            @ToolParam(description = "日程标题，1~200 字") String title,
            @ToolParam(description = "开始时间，ISO-8601，例如 2026-10-15T15:00:00Z 或 2026-10-15") String startsAt,
            @ToolParam(description = "结束时间，ISO-8601，不得早于开始时间") String endsAt,
            @ToolParam(required = false, description = "地点，可省略") String location,
            @ToolParam(required = false, description = "描述，可省略") String description,
            @ToolParam(required = false,
                    description = "是否全天日程，默认 false；为 true 时起止时间会被归一化到当天")
            Boolean allDay,
            @ToolParam(required = false,
                    description = "重复类型：NONE（默认）/ DAILY / WEEKLY / MONTHLY / WEEKDAYS")
            String recurrenceType,
            @ToolParam(required = false, description = "重复截止时间，ISO-8601；省略表示无限重复")
            String recurrenceUntil,
            @ToolParam(required = false, description = "提前多少分钟提醒，默认 15，最大 10080")
            Integer reminderLeadMinutes,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("title", title);
            args.put("startsAt", startsAt);
            args.put("endsAt", endsAt);
            args.put("location", location);
            args.put("description", description);
            args.put("allDay", allDay);
            args.put("recurrenceType", recurrenceType);
            args.put("recurrenceUntil", recurrenceUntil);
            args.put("reminderLeadMinutes", reminderLeadMinutes);
        });

        return toolExecutor.write("createCalendarEvent", arguments, toolContext, () -> {
            Instant start = ToolArguments.parseInstant(startsAt, "startsAt");
            Instant end = ToolArguments.parseInstant(endsAt, "endsAt");
            if (start == null || end == null) {
                return ToolExecutionResult.rejected("开始时间与结束时间都不能为空");
            }
            CalendarEventDto created = eventService.create(new CreateEventRequest(
                    title, description, location, allDay, start, end,
                    ToolArguments.parseEnum(RecurrenceType.class, recurrenceType, "重复类型",
                            "NONE / DAILY / WEEKLY / MONTHLY / WEEKDAYS", false),
                    ToolArguments.parseInstant(recurrenceUntil, "recurrenceUntil"),
                    reminderLeadMinutes));
            return ToolExecutionResult.ok("已创建日程「" + created.title() + "」", created);
        });
    }

    // ------------------------------------------------------------------ 查询

    @Tool(name = "findCalendarEvents",
            description = "查询某个时间区间内的日程安排（重复日程会展开成一次次的发生）。"
                    + "用户问「我今天有什么安排」「这周忙不忙」时调用。")
    public String findCalendarEvents(
            @ToolParam(required = false, description = "区间起点，ISO-8601；省略表示从今天 00:00 开始")
            String from,
            @ToolParam(required = false, description = "区间终点，ISO-8601；省略表示起点之后 7 天")
            String to,
            @ToolParam(required = false, description = "返回条数上限，默认 100，最大 500") Integer limit,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> {
            args.put("from", from);
            args.put("to", to);
            args.put("limit", limit);
        });

        return toolExecutor.read("findCalendarEvents", arguments, toolContext, () -> {
            List<CalendarOccurrenceDto> occurrences = eventService.search(
                    ToolArguments.parseInstant(from, "from"),
                    ToolArguments.parseInstant(to, "to"),
                    0,
                    limit).items();
            String message = occurrences.isEmpty()
                    ? "这个时间段没有日程安排"
                    : "找到 " + occurrences.size() + " 项安排";
            return ToolExecutionResult.ok(message, occurrences);
        });
    }

    @Tool(name = "findDueReminders",
            description = "查看当前已经到点、尚未处理的提醒（来自日程或任务）。"
                    + "用户问「有什么要提醒我的」时调用。")
    public String findDueReminders(
            @ToolParam(required = false, description = "返回条数上限，默认 50") Integer limit,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("limit", limit));

        return toolExecutor.read("findDueReminders", arguments, toolContext, () -> {
            List<ReminderDto> reminders = reminderService.list(null, true, limit);
            String message = reminders.isEmpty()
                    ? "当前没有到点的提醒"
                    : "有 " + reminders.size() + " 条已到点的提醒";
            return ToolExecutionResult.ok(message, reminders);
        });
    }

    // ------------------------------------------------------------------ 直接修改（可逆）

    @Tool(name = "markReminderRead",
            description = "把一条提醒标记为已处理。可逆且不破坏数据，无需用户确认。")
    public String markReminderRead(
            @ToolParam(description = "提醒 id") String reminderId,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("reminderId", reminderId));

        return toolExecutor.write("markReminderRead", arguments, toolContext, () -> {
            ReminderDto reminder = reminderService.markRead(
                    ToolExecutor.parseUuid(reminderId, "reminderId"));
            return ToolExecutionResult.ok("已把提醒「" + reminder.title() + "」标记为已处理", reminder);
        });
    }

    // ------------------------------------------------------------------ 需人工确认

    @Tool(name = "deleteCalendarEvent",
            description = "申请删除一个或多个日程。这是不可逆操作：调用后不会立即删除，"
                    + "而是生成一条待确认事项，必须由用户在界面上确认后才会真正删除。"
                    + "调用后请告知用户「需要在界面上确认」，不要声称已经删除。")
    public String deleteCalendarEvent(
            @ToolParam(description = "要删除的日程 id 列表，1~50 个。可先调用 findCalendarEvents 获取 id。")
            List<String> eventIds,
            ToolContext toolContext) {

        Map<String, Object> arguments = ToolExecutor.arguments(args -> args.put("eventIds", eventIds));

        return toolExecutor.write("deleteCalendarEvent", arguments, toolContext, () -> {
            if (eventIds == null || eventIds.isEmpty()) {
                return ToolExecutionResult.rejected("至少需要一个日程标识");
            }
            if (eventIds.size() > MAX_BULK_SIZE) {
                return ToolExecutionResult.rejected("一次最多处理 " + MAX_BULK_SIZE + " 个日程，请拆分后重试");
            }
            List<UUID> ids = eventIds.stream().map(id -> ToolExecutor.parseUuid(id, "eventIds")).toList();
            List<CalendarEventDto> found = eventService.findByIds(ids);
            if (found.isEmpty()) {
                return ToolExecutionResult.rejected("指定的日程都不存在，未生成确认事项");
            }
            ConfirmationDto confirmation = confirmationService.requestDeleteEvents(
                    found.stream().map(CalendarEventDto::id).toList(),
                    found.stream().map(CalendarEventDto::title).toList(),
                    ToolExecutor.conversationId(toolContext));
            return ToolExecutionResult.requiresConfirmation(
                    "删除日程需要你确认。已生成待确认事项：" + confirmation.summary()
                            + "。请用户在界面上确认后再执行。",
                    confirmation.id().toString());
        });
    }

}
