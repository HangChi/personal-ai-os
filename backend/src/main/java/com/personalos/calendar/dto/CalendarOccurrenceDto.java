package com.personalos.calendar.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 日程的一次**发生实例**（重复规则已展开）。
 *
 * <p>刻意返回展开后的实例而不是原始定义：重复日程在区间内有多个时刻，前端要按时间顺序渲染。
 * 由服务端展开，前端无需理解重复规则 —— 重复语义只有一处实现（{@code RecurrenceCalculator}），
 * 不会前后端各写一遍、然后在某个边界上不一致。
 *
 * @param eventId              所属日程
 * @param title                标题
 * @param location             地点
 * @param allDay               是否全天
 * @param startsAt             本次开始时间
 * @param endsAt               本次结束时间
 * @param recurrenceType       重复类型
 * @param recurring            是否为重复日程中的一次（前端据此决定是否显示重复标记）
 * @param reminderLeadMinutes  提前提醒量（分钟）
 */
public record CalendarOccurrenceDto(UUID eventId,
                                    String title,
                                    String location,
                                    boolean allDay,
                                    Instant startsAt,
                                    Instant endsAt,
                                    String recurrenceType,
                                    boolean recurring,
                                    int reminderLeadMinutes) {
}
