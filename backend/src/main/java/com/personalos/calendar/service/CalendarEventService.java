package com.personalos.calendar.service;

import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.dto.CalendarOccurrenceDto;
import com.personalos.calendar.dto.CreateEventRequest;
import com.personalos.calendar.dto.UpdateEventRequest;
import com.personalos.calendar.entity.CalendarEventEntity;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.calendar.entity.ReminderSource;
import com.personalos.calendar.entity.ReminderStatus;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.repository.CalendarEventRepository;
import com.personalos.calendar.repository.ReminderRepository;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.api.PageDto;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.text.Texts;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日程的写入与查询。
 *
 * <p>重复规则只在这里展开一次，对上层（REST / Agent 工具 / 提醒扫描）暴露的都是发生实例。
 */
@Service
public class CalendarEventService {

    private static final Logger log = LoggerFactory.getLogger(CalendarEventService.class);

    /** 默认查询跨度（天） */
    private static final int DEFAULT_RANGE_DAYS = 7;

    private final CalendarEventRepository eventRepository;
    private final ReminderRepository reminderRepository;
    private final RecurrenceCalculator calculator;
    private final CalendarProperties properties;
    private final ZoneId zone;

    public CalendarEventService(CalendarEventRepository eventRepository,
                                ReminderRepository reminderRepository,
                                RecurrenceCalculator calculator,
                                CalendarProperties properties,
                                ZoneId zone) {
        this.eventRepository = eventRepository;
        this.reminderRepository = reminderRepository;
        this.calculator = calculator;
        this.properties = properties;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public CalendarEventDto create(CreateEventRequest request) {
        String title = Texts.requireNotBlank(request.title(), "标题", Texts.TITLE_MAX_LENGTH);
        boolean allDay = Boolean.TRUE.equals(request.allDay());

        Instant startsAt = request.startsAt();
        Instant endsAt = request.endsAt();
        if (allDay) {
            startsAt = startOfDay(startsAt);
            endsAt = normalizeAllDayEnd(startsAt, endsAt);
        }
        requireOrdering(startsAt, endsAt);

        RecurrenceType recurrenceType = request.recurrenceType() == null
                ? RecurrenceType.NONE : request.recurrenceType();
        Instant recurrenceUntil = request.recurrenceUntil();
        requireRecurrenceUntil(startsAt, recurrenceUntil);

        CalendarEventEntity saved = eventRepository.save(new CalendarEventEntity(
                title, Texts.trimToNull(request.description()), Texts.trimToNull(request.location()), allDay,
                startsAt, endsAt, recurrenceType, recurrenceUntil,
                resolveLeadMinutes(request.reminderLeadMinutes()), Instant.now()));
        log.info("创建日程 id={} title={} recurrence={} startsAt={}",
                saved.getId(), saved.getTitle(), recurrenceType, startsAt);
        return toDto(saved);
    }

    @Transactional
    public CalendarEventDto update(UUID id, UpdateEventRequest request) {
        CalendarEventEntity event = requireEvent(id);
        Instant now = Instant.now();

        if (request.title() != null) {
            event.setTitle(Texts.requireNotBlank(request.title(), "标题", Texts.TITLE_MAX_LENGTH));
        }
        if (request.description() != null) {
            event.setDescription(Texts.trimToNull(request.description()));
        }
        if (request.location() != null) {
            event.setLocation(Texts.trimToNull(request.location()));
        }

        boolean allDay = request.allDay() != null ? request.allDay() : Boolean.TRUE.equals(event.getAllDay());
        event.setAllDay(allDay);

        // 起止时间必须成对提供：单独改其中一个会让「结束早于开始」有可乘之机
        boolean startsProvided = request.startsAt() != null;
        boolean endsProvided = request.endsAt() != null;
        if (startsProvided != endsProvided) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "修改日程时间时必须同时提供开始时间与结束时间");
        }
        if (startsProvided) {
            Instant startsAt = request.startsAt();
            Instant endsAt = request.endsAt();
            if (allDay) {
                startsAt = startOfDay(startsAt);
                endsAt = normalizeAllDayEnd(startsAt, endsAt);
            }
            requireOrdering(startsAt, endsAt);
            event.setStartsAt(startsAt);
            event.setEndsAt(endsAt);
        } else if (allDay) {
            // 仅把日程标记为全天时，也要把既有时间归一化，避免出现「全天但跨了半天」
            Instant startsAt = startOfDay(event.getStartsAt());
            Instant endsAt = normalizeAllDayEnd(startsAt, event.getEndsAt());
            requireOrdering(startsAt, endsAt);
            event.setStartsAt(startsAt);
            event.setEndsAt(endsAt);
        }

        if (request.recurrenceType() != null) {
            event.setRecurrenceType(request.recurrenceType());
        }
        if (request.recurrenceUntil() != null) {
            requireRecurrenceUntil(event.getStartsAt(), request.recurrenceUntil());
            event.setRecurrenceUntil(request.recurrenceUntil());
        } else if (Boolean.TRUE.equals(request.clearRecurrenceUntil())) {
            event.setRecurrenceUntil(null);
        }
        if (request.reminderLeadMinutes() != null) {
            event.setReminderLeadMinutes(request.reminderLeadMinutes());
        }

        event.touch(now);
        CalendarEventDto dto = toDto(eventRepository.save(event));

        // 提醒是「标题 + 发生时刻 + 提前量」的快照，来源变了就必须重建，
        // 否则：改时间照常弹旧时间（还会多弹一条新的）、改标题弹旧标题、
        // 改提前量则因为 UPSERT 是 DO NOTHING 而完全不生效。
        // 只删 PENDING：已读记录是「确实提醒过」的历史，删了会让它被重新物化、重复打扰。
        int cleared = reminderRepository.deleteBySourceAndStatus(
                ReminderSource.CALENDAR_EVENT, id, ReminderStatus.PENDING);
        if (cleared > 0) {
            log.info("日程已更新 id={}，清理待提醒 {} 条，下一轮扫描按新规则重建", id, cleared);
        }
        return dto;
    }

    /**
     * 删除日程，并一并删除它的提醒。
     *
     * <p><b>顺序：先删提醒、再删日程</b>。反过来一旦第二步之后失败，
     * 残留的提醒将无法再按 {@code source_id} 定位清理（它指向一条已不存在的日程）。
     * 当前顺序下最坏情况是「日程还在但提醒被删了」，下次扫描会重新物化 —— 这是可恢复的失败。
     *
     * <p>这里做的是**主动清理**，而不是把正确性寄托在扫描的自愈上：
     * 主动清理让「删除后立刻查不到提醒」成立，不需要等一个扫描周期。
     * 扫描里的孤儿清理是兜底，负责那些不经过本方法的删除路径。
     *
     * @return 被删除的提醒条数
     */
    @Transactional
    public int delete(UUID id) {
        CalendarEventEntity event = requireEvent(id);
        int deletedReminders = reminderRepository.deleteBySource(ReminderSource.CALENDAR_EVENT, id);
        eventRepository.deleteById(id);
        log.info("删除日程 id={} title={} 同时删除提醒 {} 条", id, event.getTitle(), deletedReminders);
        return deletedReminders;
    }

    // ------------------------------------------------------------------ 读

    @Transactional(readOnly = true)
    public CalendarEventDto get(UUID id) {
        return toDto(requireEvent(id));
    }

    /**
     * 按标识批量取出日程（用于生成待确认事项时的标题快照）。
     *
     * <p>不抛「不存在」：批量里混入已删除的 id 时，调用方需要的是「实际存在哪些」，
     * 而不是整批失败。
     */
    @Transactional(readOnly = true)
    public List<CalendarEventDto> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return eventRepository.findAllById(ids).stream().map(this::toDto).toList();
    }

    /**
     * 全部日程（导出用，阶段 12）。
     *
     * <p>按开始时间升序：导出文件是给人看的，时间线顺序比「最近创建」更易读。
     *
     * <p>导出的是**日程定义**而不是展开后的发生实例 —— 重复规则可以重新展开，
     * 而导出实例会把一次「每周」日程变成几百条看不出同源的记录。
     */
    @Transactional(readOnly = true)
    public List<CalendarEventDto> listAll() {
        return eventRepository.findAll().stream()
                .sorted(Comparator.comparing(CalendarEventEntity::getStartsAt))
                .map(this::toDto)
                .toList();
    }

    /**
     * 按区间查询，返回**展开后的发生实例**（分页）。
     *
     * <p>2026-10-14 技术债清偿：{@code limit} 静默截断改为 {@code page + size} 分页，
     * 响应含 total——重复日程在大区间下的发生数很容易超过上限，
     * 原先多出来的实例会无声消失，用户翻不到「下周」。
     *
     * @param from 区间起点；为空则取「今天 00:00」（系统时区）
     * @param to   区间终点；为空则取起点 + 7 天
     * @param page 页码，从 0 开始；为空或负值按 0
     * @param size 每页条数；为空或非正用默认值，超过硬上限收敛为上限
     */
    @Transactional(readOnly = true)
    public PageDto<CalendarOccurrenceDto> search(Instant from, Instant to, Integer page, Integer size) {
        Instant effectiveFrom = from != null ? from : startOfDay(Instant.now());
        Instant effectiveTo = to != null ? to : effectiveFrom.plus(DEFAULT_RANGE_DAYS, ChronoUnit.DAYS);
        if (effectiveTo.isBefore(effectiveFrom)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "区间终点不能早于区间起点");
        }
        long spanDays = ChronoUnit.DAYS.between(effectiveFrom, effectiveTo);
        if (spanDays > properties.maxRangeDays()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "查询区间不能超过 " + properties.maxRangeDays() + " 天");
        }

        List<CalendarEventEntity> candidates = eventRepository.findCandidates(
                effectiveFrom, effectiveTo, RecurrenceType.NONE);

        List<CalendarOccurrenceDto> occurrences = new ArrayList<>();
        for (CalendarEventEntity event : candidates) {
            for (Instant start : calculator.occurrences(event.getRecurrenceType(), event.getStartsAt(),
                    event.getRecurrenceUntil(), effectiveFrom, effectiveTo)) {
                occurrences.add(new CalendarOccurrenceDto(
                        event.getId(), event.getTitle(), event.getLocation(),
                        Boolean.TRUE.equals(event.getAllDay()),
                        start, start.plusMillis(event.durationMillis()),
                        event.getRecurrenceType().name(),
                        event.getRecurrenceType() != RecurrenceType.NONE,
                        event.getReminderLeadMinutes()));
            }
        }

        occurrences.sort(Comparator.comparing(CalendarOccurrenceDto::startsAt));
        int effectivePage = page == null || page < 0 ? 0 : page;
        return PageDto.of(occurrences, effectivePage, properties.normalizeLimit(size));
    }

    @Transactional(readOnly = true)
    public CalendarEventEntity requireEvent(UUID id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("日程不存在：" + id));
    }

    // ------------------------------------------------------------------ 内部

    private void requireOrdering(Instant startsAt, Instant endsAt) {
        if (startsAt == null || endsAt == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "开始时间与结束时间不能为空");
        }
        if (endsAt.isBefore(startsAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "结束时间不能早于开始时间");
        }
    }

    private void requireRecurrenceUntil(Instant startsAt, Instant recurrenceUntil) {
        if (recurrenceUntil != null && recurrenceUntil.isBefore(startsAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "重复截止时间不能早于开始时间");
        }
    }

    private int resolveLeadMinutes(Integer requested) {
        return requested != null ? requested : (int) properties.defaultReminderLead().toMinutes();
    }

    private Instant startOfDay(Instant instant) {
        return instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
    }

    /**
     * 归一日全天日程的结束时间。
     *
     * <p>约定：全天事件的区间是 <b>[当天 00:00, 结束日的次日 00:00)</b>。
     * 因此若客户端给的结束时间落在开始当天或更早（常见写法：结束 = 当天 23:59），
     * 就补成次日 00:00 —— 否则会出现「全天事件只有一天却横跨两天」这种半天的怪状态。
     * 多天的全天事件（结束日在开始日之后）则保持原样。
     */
    private Instant normalizeAllDayEnd(Instant startOfDay, Instant rawEnd) {
        Instant endDayStart = startOfDay(rawEnd);
        return endDayStart.isAfter(startOfDay) ? endDayStart : startOfDay.plus(1, ChronoUnit.DAYS);
    }

    private CalendarEventDto toDto(CalendarEventEntity entity) {
        return new CalendarEventDto(entity.getId(), entity.getTitle(), entity.getDescription(),
                entity.getLocation(), Boolean.TRUE.equals(entity.getAllDay()),
                entity.getStartsAt(), entity.getEndsAt(), entity.getRecurrenceType().name(),
                entity.getRecurrenceUntil(), entity.getReminderLeadMinutes(), entity.getCreatedAt());
    }
}
