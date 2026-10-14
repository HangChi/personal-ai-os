package com.personalos.calendar.confirmation;

import java.util.List;
import java.util.UUID;

/**
 * 日程类待确认动作的参数体。
 *
 * <p>与任务侧同理：把标题快照一并存入，日程删除后仍能说明当时删了什么。
 */
public final class CalendarConfirmationPayloads {

    private CalendarConfirmationPayloads() {
    }

    /** 删除日程的参数。 */
    public record DeleteEvents(List<UUID> eventIds, List<String> titles) {
    }
}
