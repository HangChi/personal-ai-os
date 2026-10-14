package com.personalos.calendar.confirmation;

import com.personalos.calendar.confirmation.CalendarConfirmationPayloads.DeleteEvents;
import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationDto;
import com.personalos.common.confirmation.ConfirmationService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日程侧的「待确认事项」构造器。
 *
 * <p>与 {@code TaskConfirmationService} 同构：只负责把业务语义翻译成一段人类可读的 summary
 * 与结构化的 payload；令牌、有效期、单次性由 {@link ConfirmationService} 负责。
 */
@Service
public class CalendarConfirmationService {

    private final ConfirmationService confirmations;

    public CalendarConfirmationService(ConfirmationService confirmations) {
        this.confirmations = confirmations;
    }

    /**
     * 申请删除日程。
     *
     * @param eventIds       目标日程
     * @param titles         标题快照
     * @param conversationId 发起会话，可空
     */
    @Transactional
    public ConfirmationDto requestDeleteEvents(List<UUID> eventIds, List<String> titles,
                                              String conversationId) {
        String summary = titles.size() == 1
                ? "删除日程：" + titles.get(0)
                : "删除 " + titles.size() + " 个日程：" + String.join("、", titles);
        return confirmations.create(ConfirmationAction.DELETE_CALENDAR_EVENT, summary,
                new DeleteEvents(eventIds, titles), eventIds.size(), conversationId);
    }
}
