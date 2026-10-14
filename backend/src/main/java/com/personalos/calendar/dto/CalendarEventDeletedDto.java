package com.personalos.calendar.dto;

/**
 * 删除日程的应答。
 *
 * @param deleted           是否已删除
 * @param deletedReminders  随之删除的提醒条数（提醒只服务于来源，来源没了就该一起清掉）
 */
public record CalendarEventDeletedDto(boolean deleted, int deletedReminders) {
}
