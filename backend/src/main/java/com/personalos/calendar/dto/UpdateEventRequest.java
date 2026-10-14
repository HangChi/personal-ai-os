package com.personalos.calendar.dto;

import com.personalos.calendar.entity.RecurrenceType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * 修改日程请求；仅更新**出现**的字段。
 *
 * <p><b>为什么需要 clearRecurrenceUntil</b>：record 的 null 无法同时表达「字段未提供」与
 * 「显式置空」。若把 {@code recurrenceUntil == null} 解释成「取消重复截止」，
 * 那么只想改标题的请求会把无限重复的日程意外变成……不，这里更危险的是反向：
 * 若把「未提供」当成「清空」，一个改标题的请求会让原本「限期重复」的日程变成无限重复。
 * 因此清除意图必须显式声明。
 *
 * <p>另外，只要提供了 {@code startsAt} 或 {@code endsAt} 中的一个，就必须成对提供 ——
 * 单独改一个会让「结束早于开始」这类非法状态有可乘之机，由服务层校验。
 */
public record UpdateEventRequest(
        @Size(max = 200, message = "标题长度不能超过 200")
        String title,

        @Size(max = 2000, message = "描述长度不能超过 2000")
        String description,

        @Size(max = 200, message = "地点长度不能超过 200")
        String location,

        Boolean allDay,

        Instant startsAt,

        Instant endsAt,

        RecurrenceType recurrenceType,

        Instant recurrenceUntil,

        Boolean clearRecurrenceUntil,

        @Min(value = 0, message = "提前提醒量不能为负")
        @Max(value = 10080, message = "提前提醒量不能超过 7 天（10080 分钟）")
        Integer reminderLeadMinutes) {
}
