package com.personalos.calendar.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 提醒视图。
 *
 * @param id           提醒标识
 * @param sourceType   来源类型：CALENDAR_EVENT / TASK
 * @param sourceId     来源标识
 * @param title        标题快照（来源被改名或删除后仍可说明当时提醒的是什么）
 * @param occurrenceAt 发生时刻（日程的该次开始时间 / 任务的截止时间）
 * @param remindAt     提醒时刻
 * @param status       状态：PENDING / READ / CANCELLED
 * @param readAt       读取时间，可空
 */
public record ReminderDto(UUID id,
                          String sourceType,
                          UUID sourceId,
                          String title,
                          Instant occurrenceAt,
                          Instant remindAt,
                          String status,
                          Instant readAt) {
}
