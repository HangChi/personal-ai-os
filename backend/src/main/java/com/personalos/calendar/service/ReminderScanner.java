package com.personalos.calendar.service;

import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.dto.ReminderScanResult;
import com.personalos.calendar.entity.CalendarEventEntity;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.calendar.entity.ReminderSource;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.repository.CalendarEventRepository;
import com.personalos.calendar.repository.ReminderRepository;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提醒物化：把「应该存在的提醒」补齐。
 *
 * <h2>为什么是「扫描并物化」而不是「到点触发」</h2>
 * 提醒是否到点由 {@code remind_at <= now} 在**查询时**判定，而不是靠一次状态跃迁。
 * 扫描的职责只是把窗口内的发生时刻变成提醒记录，因此它天然是**幂等**的：
 * 反复运行只会得到同样的结果，被唯一约束挡下的次数以 {@code skipped} 体现。
 *
 * <h2>窗口为什么两端都要外扩</h2>
 * <ul>
 *   <li><b>向前看（lookahead）</b>：提醒时刻 = 发生时刻 − 提前量。若提前量大于窗口长度，
 *       那么「已经该提醒、但发生时刻还在窗口外」的日程会被漏掉。窗口必须覆盖提前量 ——
 *       所以日程用的是 {@code effectiveScanLookahead()}（= max(lookahead, 最长提前量)），
 *       而不是配置里的 lookahead 本身。</li>
 *   <li><b>向后看（lookback）</b>：应用停机期间到点的提醒，重启后仍要补上。
 *       回看窗口决定了能弥补多久的停机 —— 默认 24 小时，足以覆盖日常重启；
 *       刻意不设成一周：补发一周前的提醒只会制造噪音，而不是「更不丢数据」。</li>
 * </ul>
 *
 * <h2>为什么同时扫任务</h2>
 * 用户要求「日程与任务都能提醒」。这里**没有**采用「任务变更时发事件创建提醒」的做法：
 * 那种方式下任何一条漏发事件的新代码路径都会让提醒静默消失。
 * 扫描式是兜底重算 —— 漏了下一轮自动补上，而且让 {@code task} 模块无需知道日程的存在。
 */
@Service
public class ReminderScanner {

    private static final Logger log = LoggerFactory.getLogger(ReminderScanner.class);

    private final CalendarEventRepository eventRepository;
    private final ReminderRepository reminderRepository;
    private final RecurrenceCalculator calculator;
    private final TaskService taskService;
    private final CalendarProperties properties;

    public ReminderScanner(CalendarEventRepository eventRepository,
                           ReminderRepository reminderRepository,
                           RecurrenceCalculator calculator,
                           TaskService taskService,
                           CalendarProperties properties) {
        this.eventRepository = eventRepository;
        this.reminderRepository = reminderRepository;
        this.calculator = calculator;
        this.taskService = taskService;
        this.properties = properties;
    }

    /**
     * 扫描一次。
     *
     * @param now 当前时刻（由调用方传入，因此测试可以推进时间而不必真的等待）
     */
    @Transactional
    public ReminderScanResult scan(Instant now) {
        Instant windowStart = now.minus(properties.scanLookback());
        Instant windowEnd = now.plus(properties.scanLookahead());
        // 日程用「至少覆盖最长提前量」的窗口（见 CalendarProperties#effectiveScanLookahead）。
        // 任务仍用常规窗口：任务的提前量只有 defaultReminderLead（十几分钟），
        // 而把任务窗口也拉到 7 天会凭空物化一批远期提醒，让「接下来会提醒什么」的列表
        // 塞满下周才到期的东西。
        Instant eventWindowEnd = now.plus(properties.effectiveScanLookahead());

        int created = 0;
        int skipped = 0;

        // ⓪ 同步任务快照并清理失效来源；task 模块无需反向依赖提醒模块。
        //    放在物化之前，改期后的新提醒可以在同一轮扫描中重建。
        int pruned = reminderRepository.pruneInvalidTaskReminders();
        pruned += reminderRepository.pruneOrphans(toOffset(windowEnd));
        reminderRepository.refreshTaskTitles();
        if (pruned > 0) {
            log.info("清理失效提醒 {} 条", pruned);
        }

        // ① 日程：窗口内每一次发生都物化（含尚未到点的，便于用户提前看到「接下来会提醒什么」）
        List<CalendarEventEntity> events =
                eventRepository.findCandidates(windowStart, eventWindowEnd, RecurrenceType.NONE);
        for (CalendarEventEntity event : events) {
            long leadSeconds = event.getReminderLeadMinutes() * 60L;
            List<Instant> occurrences = calculator.occurrences(event.getRecurrenceType(),
                    event.getStartsAt(), event.getRecurrenceUntil(), windowStart, eventWindowEnd);
            for (Instant occurrence : occurrences) {
                Instant remindAt = occurrence.minusSeconds(leadSeconds);
                int inserted = reminderRepository.insertIfAbsent(
                        ReminderSource.CALENDAR_EVENT.name(), event.getId(), event.getTitle(),
                        toOffset(occurrence), toOffset(remindAt));
                if (inserted > 0) {
                    created++;
                } else {
                    skipped++;
                }
            }
        }

        // ② 任务：未完成且截止时间落在窗口内
        long taskLeadSeconds = properties.defaultReminderLead().toSeconds();
        List<TaskDto> dueTasks = taskService.findDueBetween(windowStart, windowEnd);
        for (TaskDto task : dueTasks) {
            Instant remindAt = task.dueAt().minusSeconds(taskLeadSeconds);
            int inserted = reminderRepository.insertIfAbsent(
                    ReminderSource.TASK.name(), task.id(), task.title(),
                    toOffset(task.dueAt()), toOffset(remindAt));
            if (inserted > 0) {
                created++;
            } else {
                skipped++;
            }
        }

        if (created > 0) {
            log.info("提醒扫描完成 window=[{}, {}] eventWindow=[{}, {}] 新增={} 已存在={} events={} tasks={}",
                    windowStart, windowEnd, windowStart, eventWindowEnd,
                    created, skipped, events.size(), dueTasks.size());
        } else {
            log.debug("提醒扫描完成 window=[{}, {}] 新增=0 已存在={}", windowStart, windowEnd, skipped);
        }
        return new ReminderScanResult(windowStart, windowEnd, now, created, skipped, pruned);
    }

    /**
     * 绑定原生 SQL 参数时必须用 {@link OffsetDateTime}：
     * {@link Instant} 会被绑成无时区的 {@code TIMESTAMP}，PostgreSQL 随后按会话时区隐式转换，
     * 值可能被平移且**不报错**。
     */
    private OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
