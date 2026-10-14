package com.personalos.calendar.service;

import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.dto.ReminderDto;
import com.personalos.calendar.entity.ReminderEntity;
import com.personalos.calendar.entity.ReminderStatus;
import com.personalos.calendar.repository.ReminderRepository;
import com.personalos.common.exception.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒的查询与已读标记。
 */
@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository repository;
    private final CalendarProperties properties;
    private final Clock clock;

    public ReminderService(ReminderRepository repository, CalendarProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 查询提醒。
     *
     * @param status  状态；为空默认 {@code PENDING}
     * @param dueOnly 仅返回**已到点**的（{@code remind_at <= now}）。
     *                设为 false 返回全部（含将来要提醒的）—— 这样既能提前看到「接下来会提醒什么」，
     *                也便于人工验证扫描是否已经把下一条物化出来，否则只能干等到点。
     * @param limit   条数上限
     */
    @Transactional(readOnly = true)
    public List<ReminderDto> list(ReminderStatus status, boolean dueOnly, Integer limit) {
        ReminderStatus effectiveStatus = status == null ? ReminderStatus.PENDING : status;
        PageRequest page = PageRequest.of(0, properties.normalizeLimit(limit));
        Instant now = clock.instant();

        List<ReminderEntity> found;
        if (effectiveStatus == ReminderStatus.PENDING && dueOnly) {
            // 「已过点但未读」必须仍然返回：提醒的意义在于「你还没看到它」，
            // 而不是「它恰好在这一秒到点」—— 用 <= now（而不是一个窄区间）表达这一点
            found = repository.findByStatusAndRemindAtLessThanEqualOrderByRemindAtAsc(
                    effectiveStatus, now, page);
        } else {
            // dueOnly=false 时不再按时间过滤，返回全部 —— 参数名是「不按到点过滤」，
            // 若这里只返回将来的，就变成了「只看到点的反面」，与名字不符且会误导排查者
            found = repository.findByStatusOrderByRemindAtAsc(effectiveStatus, page);
        }
        return found.stream().map(this::toDto).toList();
    }

    /**
     * 标记已读。
     *
     * <p><b>幂等</b>：重复标记同一提醒返回 200 且状态仍为已读，不报冲突。
     * 用户可能重复点击，轮询期间页面也可能重发 —— 把它当错误只会制造无意义的报错。
     * 已经读过时保留**首次**阅读时间，那才是有意义的时刻。
     */
    @Transactional
    public ReminderDto markRead(UUID id) {
        ReminderEntity reminder = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("提醒不存在：" + id));
        if (reminder.markRead(clock.instant())) {
            log.debug("提醒标记已读 id={}", id);
            repository.save(reminder);
        }
        return toDto(reminder);
    }

    private ReminderDto toDto(ReminderEntity entity) {
        return new ReminderDto(entity.getId(), entity.getSourceType().name(), entity.getSourceId(),
                entity.getTitle(), entity.getOccurrenceAt(), entity.getRemindAt(),
                entity.getStatus().name(), entity.getReadAt());
    }
}
