package com.personalos.calendar.dto;

import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.entity.RecurrenceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 创建日程请求。
 *
 * @param title               标题，必填
 * @param description         描述
 * @param location            地点
 * @param allDay              是否全天；为 true 时服务端会归一化起止时间
 * @param startsAt            开始时间，必填
 * @param endsAt              结束时间，必填，不得早于开始时间
 * @param recurrenceType      重复类型，省略为不重复
 * @param recurrenceUntil     重复截止（含），可空表示无限
 * @param reminderLeadMinutes 提前提醒量（分钟），默认 15
 */
public record CreateEventRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "描述长度不能超过 2000")
        String description,

        @Size(max = 200, message = "地点长度不能超过 200")
        String location,

        Boolean allDay,

        @NotNull(message = "开始时间不能为空")
        Instant startsAt,

        @NotNull(message = "结束时间不能为空")
        Instant endsAt,

        RecurrenceType recurrenceType,

        Instant recurrenceUntil,

        @Min(value = 0, message = "提前提醒量不能为负")
        @Max(value = CalendarProperties.MAX_REMINDER_LEAD_MINUTES,
                message = "提前提醒量不能超过 7 天（10080 分钟）")
        Integer reminderLeadMinutes) {
}
