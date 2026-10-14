package com.personalos.calendar.confirmation;

import com.personalos.calendar.confirmation.CalendarConfirmationPayloads.DeleteEvents;
import com.personalos.calendar.repository.CalendarEventRepository;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationActionHandler;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日程类待确认动作的执行处理器。
 *
 * <p>它是「确认机制泛化」的第一个新增使用方：这个类放在日程模块里，
 * 而通用的 {@code ConfirmationService} 完全不需要知道日程的存在。
 */
@Component
public class CalendarConfirmationHandler implements ConfirmationActionHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarConfirmationHandler.class);

    private final CalendarEventRepository eventRepository;
    private final CalendarEventService eventService;
    private final JsonCodec jsonCodec;

    public CalendarConfirmationHandler(CalendarEventRepository eventRepository,
                                       CalendarEventService eventService,
                                       JsonCodec jsonCodec) {
        this.eventRepository = eventRepository;
        this.eventService = eventService;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Set<ConfirmationAction> supportedActions() {
        return Set.of(ConfirmationAction.DELETE_CALENDAR_EVENT);
    }

    @Override
    public int execute(ConfirmationAction action, String payloadJson) {
        DeleteEvents payload = jsonCodec.fromJson(payloadJson, DeleteEvents.class);

        int affected = 0;
        for (UUID eventId : payload.eventIds()) {
            // 逐个判断存在性：批量删除时其中一个已被删掉不应该让整批失败，
            // 但也必须如实反馈「实际删掉了几个」，而不是假装全都删了
            if (!eventRepository.existsById(eventId)) {
                log.debug("待确认删除的日程已不存在，跳过 id={}", eventId);
                continue;
            }
            eventService.delete(eventId);
            affected++;
        }

        if (affected == 0) {
            // 与任务侧一致：区分「执行成功但没影响任何行」与「已无可执行目标」
            throw new ResourceNotFoundException("目标日程已不存在");
        }
        return affected;
    }
}
