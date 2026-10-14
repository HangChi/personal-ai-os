package com.personalos.calendar.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 日程的**原始定义**（不展开重复）。
 *
 * @param id                   日程标识
 * @param title                标题
 * @param description          描述
 * @param location             地点
 * @param allDay               是否全天
 * @param startsAt             首次开始时间
 * @param endsAt               首次结束时间
 * @param recurrenceType       重复类型：NONE / DAILY / WEEKLY / MONTHLY / WEEKDAYS
 * @param recurrenceUntil      重复截止（含），可空表示无限
 * @param reminderLeadMinutes  提前提醒量（分钟）
 * @param createdAt            创建时间
 */
public record CalendarEventDto(UUID id,
                               String title,
                               String description,
                               String location,
                               boolean allDay,
                               Instant startsAt,
                               Instant endsAt,
                               String recurrenceType,
                               Instant recurrenceUntil,
                               int reminderLeadMinutes,
                               Instant createdAt) {
}
