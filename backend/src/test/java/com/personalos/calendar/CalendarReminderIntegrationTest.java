package com.personalos.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.calendar.config.CalendarProperties;
import com.personalos.calendar.dto.CalendarEventDto;
import com.personalos.calendar.dto.CalendarOccurrenceDto;
import com.personalos.calendar.dto.CreateEventRequest;
import com.personalos.calendar.dto.ReminderDto;
import com.personalos.calendar.dto.ReminderScanResult;
import com.personalos.calendar.dto.UpdateEventRequest;
import com.personalos.calendar.entity.RecurrenceType;
import com.personalos.calendar.entity.ReminderSource;
import com.personalos.calendar.recurrence.RecurrenceCalculator;
import com.personalos.calendar.repository.CalendarEventRepository;
import com.personalos.calendar.repository.ReminderRepository;
import com.personalos.calendar.service.CalendarEventService;
import com.personalos.calendar.service.ReminderScanner;
import com.personalos.calendar.service.ReminderService;
import com.personalos.common.api.PageDto;
import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.support.HttpTestClient;
import com.personalos.support.HttpTestClient.SseEvent;
import com.personalos.support.Instants;
import com.personalos.support.ScriptedToolCallingChatModel;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.entity.TaskStatus;
import com.personalos.task.repository.TaskRepository;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 日程与提醒的集成测试（SC-004 ~ SC-009）。
 *
 * <h2>时间是怎么控制的</h2>
 * 扫描入口是 {@code ReminderScanner.scan(Instant now)} —— **时间由调用方传入**，
 * 因此这里可以任意「推进时间」，全程**没有一处 {@code Thread.sleep}**。
 * 查询侧（{@code ReminderService.list}）用的是注入的 {@code Clock}，即真实时间；
 * 所以构造测试数据时把「应该已经到点」的提醒放在过去，把「还没到点」的放在将来。
 *
 * <p>调度器在测试里被 {@code app.calendar.scan-enabled=false} 关掉了
 * （见 {@code src/test/resources/application.properties}），否则它会在用例中途写库，
 * 与 {@code @BeforeEach} 的清理相互干扰。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.model.chat=none"})
@Testcontainers
class CalendarReminderIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubConfiguration {
        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private final HttpTestClient http;
    private final CalendarEventService eventService;
    private final ReminderService reminderService;
    private final ReminderScanner scanner;
    private final CalendarEventRepository eventRepository;
    private final ReminderRepository reminderRepository;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ConfirmationService confirmationService;
    private final ConfirmationRepository confirmationRepository;
    private final OperationRecordRepository operationRecordRepository;
    private final RecurrenceCalculator calculator;
    private final CalendarProperties properties;
    private final ScriptedToolCallingChatModel model;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    CalendarReminderIntegrationTest(@Value("${local.server.port}") int port,
                                    CalendarEventService eventService,
                                    ReminderService reminderService,
                                    ReminderScanner scanner,
                                    CalendarEventRepository eventRepository,
                                    ReminderRepository reminderRepository,
                                    TaskService taskService,
                                    TaskRepository taskRepository,
                                    ConfirmationService confirmationService,
                                    ConfirmationRepository confirmationRepository,
                                    OperationRecordRepository operationRecordRepository,
                                    RecurrenceCalculator calculator,
                                    CalendarProperties properties,
                                    ScriptedToolCallingChatModel model,
                                    TransactionTemplate transactionTemplate) {
        this.http = new HttpTestClient(port);
        this.eventService = eventService;
        this.reminderService = reminderService;
        this.scanner = scanner;
        this.eventRepository = eventRepository;
        this.reminderRepository = reminderRepository;
        this.taskService = taskService;
        this.taskRepository = taskRepository;
        this.confirmationService = confirmationService;
        this.confirmationRepository = confirmationRepository;
        this.operationRecordRepository = operationRecordRepository;
        this.calculator = calculator;
        this.properties = properties;
        this.model = model;
        this.transactionTemplate = transactionTemplate;
    }

    @BeforeEach
    void cleanUp() {
        // 顺序：先清提醒与确认（它们引用别的东西），再清各自的主体
        reminderRepository.deleteAll();
        eventRepository.deleteAll();
        confirmationRepository.deleteAll();
        taskRepository.deleteAll();
        operationRecordRepository.deleteAll();
        model.reset();
    }

    // ------------------------------------------------------------------ 日程 CRUD 与重复展开

    @Test
    @DisplayName("分页：重复日程的实例切片返回，total 是展开后的全量实例数")
    void occurrencesArePaginatedWithTotal() {
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        eventService.create(new CreateEventRequest(
                "每周站会", null, null, false, startsAt, startsAt.plus(30, ChronoUnit.MINUTES),
                RecurrenceType.WEEKLY, null, 15));

        PageDto<CalendarOccurrenceDto> firstPage = eventService.search(
                startsAt.minus(1, ChronoUnit.HOURS), startsAt.plus(22, ChronoUnit.DAYS), 0, 2);

        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.total()).isEqualTo(4);
        assertThat(firstPage.page()).isZero();
        assertThat(firstPage.size()).isEqualTo(2);

        PageDto<CalendarOccurrenceDto> beyond = eventService.search(
                startsAt.minus(1, ChronoUnit.HOURS), startsAt.plus(22, ChronoUnit.DAYS), 9, 2);

        assertThat(beyond.items()).isEmpty();
        assertThat(beyond.total()).isEqualTo(4);
    }

    @Test
    @DisplayName("创建每周重复日程：区间查询返回展开后的多次发生，且按时间升序")
    void weeklyEventExpandsIntoOccurrences() {
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        CalendarEventDto event = eventService.create(new CreateEventRequest(
                "每周站会", null, "会议室", false, startsAt, startsAt.plus(30, ChronoUnit.MINUTES),
                RecurrenceType.WEEKLY, null, 15));

        List<CalendarOccurrenceDto> occurrences = eventService.search(
                startsAt.minus(1, ChronoUnit.HOURS), startsAt.plus(22, ChronoUnit.DAYS), 0, null).items();

        assertThat(occurrences).hasSize(4);
        assertThat(occurrences).extracting(CalendarOccurrenceDto::eventId)
                .containsOnly(event.id());
        assertThat(occurrences).extracting(CalendarOccurrenceDto::startsAt)
                .isSortedAccordingTo(Comparator.naturalOrder());
        assertThat(occurrences).allSatisfy(occurrence -> {
            assertThat(occurrence.recurring()).isTrue();
            // 每次持续 30 分钟，展开时必须保持时长
            assertThat(occurrence.endsAt()).isEqualTo(occurrence.startsAt().plus(30, ChronoUnit.MINUTES));
        });
    }

    @Test
    @DisplayName("全天日程的时间被归一化到 [当天 00:00, 次日 00:00)")
    void allDayEventIsNormalized() {
        Instant someTimeToday = Instant.now().plus(5, ChronoUnit.HOURS);

        CalendarEventDto event = eventService.create(new CreateEventRequest(
                "休假", null, null, true, someTimeToday, someTimeToday.plus(1, ChronoUnit.HOURS),
                RecurrenceType.NONE, null, 15));

        long spanHours = ChronoUnit.HOURS.between(event.startsAt(), event.endsAt());
        assertThat(spanHours).as("全天事件应恰好覆盖 24 小时").isEqualTo(24);
        assertThat(event.startsAt().atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString())
                .as("应归一到当天 00:00")
                .startsWith("00:00");
    }

    // ------------------------------------------------------------------ SC-009：非法输入零写入

    @Test
    @DisplayName("非法时间（结束早于开始 / 重复截止早于开始）→ 零写入（SC-009）")
    void invalidTimesProduceZeroWrites() throws Exception {
        Instant startsAt = Instant.now().plus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> eventService.create(new CreateEventRequest(
                "时间颠倒", null, null, false, startsAt, startsAt.minus(1, ChronoUnit.HOURS),
                RecurrenceType.NONE, null, 15)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("结束时间");

        assertThatThrownBy(() -> eventService.create(new CreateEventRequest(
                "截止早于开始", null, null, false, startsAt, startsAt.plus(1, ChronoUnit.HOURS),
                RecurrenceType.DAILY, startsAt.minus(1, ChronoUnit.DAYS), 15)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重复截止");

        assertThat(eventRepository.count()).as("非法请求不得留下任何日程").isZero();

        // HTTP 层同样应返回参数错误而不是 500
        HttpTestClient.Response response = http.post("/api/v1/calendar/events",
                "{\"title\":\"x\",\"startsAt\":\"2026-12-01T10:00:00Z\",\"endsAt\":\"2026-12-01T09:00:00Z\"}");
        assertThat(response.status()).isEqualTo(400);
        assertThat(eventRepository.count()).isZero();
    }

    // ------------------------------------------------------------------ SC-004 / SC-005 / SC-008

    @Test
    @DisplayName("扫描物化到点提醒：出现在待提醒列表，且唯一约束保证只有一条（SC-008）")
    void scanMaterializesDueReminderOnce() {
        Instant now = Instant.now();
        UUID eventId = createEvent("刚开完的会", now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.MINUTES), 15);

        ReminderScanResult result = scanner.scan(now);

        assertThat(result.created()).as("窗口内应物化出该日程的提醒").isEqualTo(1);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.CALENDAR_EVENT, eventId))
                .isEqualTo(1);

        List<ReminderDto> due = reminderService.list(null, true, null);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).title()).isEqualTo("刚开完的会");
        assertThat(due.get(0).sourceType()).isEqualTo(ReminderSource.CALENDAR_EVENT.name());
    }

    @Test
    @DisplayName("连续扫描 5 次，提醒行数与扫描 1 次完全相同（SC-004）")
    void repeatedScansAreIdempotent() {
        Instant now = Instant.now();
        createEvent("重复扫描", now.minus(1, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES), 15);

        scanner.scan(now);
        long afterFirst = reminderRepository.count();
        assertThat(afterFirst).isEqualTo(1);

        ReminderScanResult last = null;
        for (int round = 0; round < 4; round++) {
            last = scanner.scan(now);
        }

        assertThat(reminderRepository.count()).as("重复扫描不得新增提醒").isEqualTo(afterFirst);
        assertThat(last).isNotNull();
        assertThat(last.created()).as("后续扫描不应再有新增").isZero();
        assertThat(last.skipped()).as("应体现为「已存在被忽略」").isEqualTo(1);
    }

    @Test
    @DisplayName("用一个全新的扫描器实例再扫，行数不变（等价于应用重启，SC-005）")
    void freshScannerInstanceSeesSameRows() {
        Instant now = Instant.now();
        createEvent("重启验证", now.minus(1, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES), 15);
        scanner.scan(now);
        long before = reminderRepository.count();

        // 扫描器是**无状态**的：全部状态都在数据库里，因此「重启」（换一个全新的实例）不会改变结果。
        // 这正是当初选择「DB 状态驱动的周期扫描」而不是「内存定时任务」的原因 ——
        // 后者重启即丢，用户会静默地收不到提醒。
        //
        // 注意：手工 new 出来的实例不是 Spring 代理，@Transactional 不会生效，
        // 因此这里必须自己包一层事务（这本身也提醒了「声明式事务是基于代理的」）。
        ReminderScanner restarted = new ReminderScanner(
                eventRepository, reminderRepository, calculator, taskService, properties);
        transactionTemplate.executeWithoutResult(status -> restarted.scan(now));

        assertThat(reminderRepository.count()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ 到点与否

    @Test
    @DisplayName("未到点的提醒会被提前物化，但不出现在「待提醒」列表里")
    void futureReminderIsMaterializedButNotDue() {
        Instant now = Instant.now();
        createEvent("两小时后的会", now.plus(2, ChronoUnit.HOURS), now.plus(3, ChronoUnit.HOURS), 15);

        scanner.scan(now);

        assertThat(reminderRepository.count()).as("窗口内的将来发生也应物化").isEqualTo(1);
        assertThat(reminderService.list(null, true, null))
                .as("尚未到点，不应出现在待提醒列表")
                .isEmpty();
        assertThat(reminderService.list(null, false, null))
                .as("dueOnly=false 表示「不按到点过滤」，应能看到全部（含将来要提醒的）")
                .hasSize(1);
    }

    @Test
    @DisplayName("已过点但未读的提醒仍然展示（FR-015）")
    void overdueUnreadReminderIsStillVisible() {
        Instant now = Instant.now();
        // 发生时刻在两小时前，提醒时刻在更早 —— 早已「过点」
        createEvent("两小时前错过的会", now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS), 15);

        scanner.scan(now);

        assertThat(reminderService.list(null, true, null))
                .as("提醒的意义是「你还没看到它」，而不是「它恰好在这一秒到点」")
                .hasSize(1);
    }

    @Test
    @DisplayName("标记已读是幂等的：重复标记仍为已读，且不再出现在待提醒列表")
    void markReadIsIdempotent() {
        Instant now = Instant.now();
        createEvent("读两次", now.minus(1, ChronoUnit.MINUTES), now.plus(30, ChronoUnit.MINUTES), 15);
        scanner.scan(now);

        UUID reminderId = reminderRepository.findAll().get(0).getId();

        ReminderDto first = reminderService.markRead(reminderId);
        ReminderDto second = reminderService.markRead(reminderId);

        assertThat(first.status()).isEqualTo("READ");
        assertThat(second.status()).isEqualTo("READ");
        // 首次 markRead 返回的是**内存里**刚落的时间（纳秒），第二次是从库里读回的（微秒），
        // 直接 equals 会在 Linux 上挂（JVM 时钟到纳秒，TIMESTAMPTZ 只存到微秒）。
        // 要验证的是「第二次没有覆盖首次」，那就按存储精度比较。
        assertThat(Instants.sameAtStoragePrecision(second.readAt(), first.readAt()))
                .as("重复标记应保留首次阅读时间")
                .isTrue();
        assertThat(reminderService.list(null, true, null)).isEmpty();
    }

    // ------------------------------------------------------------------ SC-006：任务来源

    @Test
    @DisplayName("任务也能提醒；已完成的**不再产生**新提醒（FR-013 / FR-014 / SC-006）")
    void taskRemindersSkipCompletedTasks() {
        Instant now = Instant.now();
        Instant dueAt = now.plus(30, ChronoUnit.MINUTES);

        TaskDto openTask = taskService.create(new CreateTaskRequest("未完成的交周报", null, null, dueAt, null));
        TaskDto doneTask = taskService.create(new CreateTaskRequest("已完成的交月报", null, null, dueAt, null));
        taskService.setCompleted(doneTask.id(), true);

        scanner.scan(now);

        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.TASK, openTask.id()))
                .as("未完成任务应产生提醒")
                .isEqualTo(1);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.TASK, doneTask.id()))
                .as("已完成任务不应产生提醒")
                .isZero();
        assertThat(taskRepository.findById(doneTask.id()).orElseThrow().getStatus())
                .isEqualTo(TaskStatus.DONE);
    }

    // ------------------------------------------------------------------ SC-007：删除清理

    @Test
    @DisplayName("修改日程后 PENDING 提醒被清理并按新规则重建：不会同时收到旧时间与新时间两条")
    void updatingEventRebuildsPendingReminders() {
        Instant now = Instant.now();
        UUID eventId = createEvent("周会", now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.MINUTES), 15);
        scanner.scan(now);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.CALENDAR_EVENT, eventId))
                .isEqualTo(1);

        Instant moved = now.plus(3, ChronoUnit.HOURS);
        eventService.update(eventId, new UpdateEventRequest(
                "周会（改期）", null, null, false,
                moved, moved.plus(30, ChronoUnit.MINUTES),
                null, null, null, 15));

        // 提醒是快照：不清掉旧的，下一轮扫描会再插一条新的，
        // 用户会同时收到「旧时间」与「新时间」两条提醒，而且旧标题也会照旧弹出
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.CALENDAR_EVENT, eventId))
                .as("修改后旧提醒应被清理，等下一轮扫描重建")
                .isZero();

        scanner.scan(now);

        List<ReminderDto> all = reminderService.list(null, false, null).stream()
                .filter(reminder -> eventId.equals(reminder.sourceId()))
                .toList();
        assertThat(all).as("重建后只应有一条").hasSize(1);
        assertThat(all.get(0).title()).isEqualTo("周会（改期）");
        assertThat(Instants.sameAtStoragePrecision(all.get(0).occurrenceAt(), moved)).isTrue();
    }

    @Test
    @DisplayName("已读的提醒不会因为改日程被删掉重发")
    void updatingEventKeepsReadReminders() {
        Instant now = Instant.now();
        UUID eventId = createEvent("已读过的会", now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.MINUTES), 15);
        scanner.scan(now);
        UUID reminderId = reminderService.list(null, true, null).get(0).id();
        reminderService.markRead(reminderId);

        eventService.update(eventId, new UpdateEventRequest(
                "已读过的会", null, null, false, null, null, null, null, null, 30));

        // 已读记录是「确实提醒过」的历史事实；删掉它会让同一发生时刻被重新物化，
        // 用户会再收到一条本该已经读过的提醒
        assertThat(reminderRepository.findById(reminderId))
                .as("已读提醒必须保留")
                .isPresent();
    }

    @Test
    @DisplayName("提前 7 天的提醒立刻物化：扫描窗口必须覆盖最大提前量，否则会晚到数天")
    void longLeadReminderIsMaterializedImmediately() {
        Instant now = Instant.now();
        Instant startsAt = now.plus(5, ChronoUnit.DAYS);
        createEvent("五天后的会", startsAt, startsAt.plus(1, ChronoUnit.HOURS),
                CalendarProperties.MAX_REMINDER_LEAD_MINUTES);

        ReminderScanResult result = scanner.scan(now);

        assertThat(result.created()).as("窗口内应物化出该日程的提醒").isEqualTo(1);
        // 提醒时刻 = 5 天后 − 7 天 = 2 天前，所以它**现在就该是到点的**。
        // 若扫描窗口仍是 24h，这条要等到事件前一天才出现，且一出现就已过期 —— 晚 6 天。
        assertThat(reminderService.list(null, true, null))
                .as("提前量大于扫描窗口时，提醒不该晚到")
                .anyMatch(reminder -> reminder.title().equals("五天后的会"));
    }

    @Test
    @DisplayName("删除日程时它的提醒一并删除，条数为 0（SC-007）")
    void deletingEventRemovesItsReminders() {
        Instant now = Instant.now();
        UUID eventId = createEvent("要删的会", now.minus(1, ChronoUnit.MINUTES),
                now.plus(30, ChronoUnit.MINUTES), 15);
        scanner.scan(now);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.CALENDAR_EVENT, eventId))
                .isEqualTo(1);

        int deletedReminders = eventService.delete(eventId);

        assertThat(deletedReminders).isEqualTo(1);
        assertThat(eventRepository.existsById(eventId)).isFalse();
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.CALENDAR_EVENT, eventId))
                .isZero();
        assertThat(reminderService.list(null, true, null)).isEmpty();
    }

    @Test
    @DisplayName("删除任务后，扫描会清掉它的孤儿提醒（否则用户会收到指向已删任务的幽灵提醒）")
    void deletingTaskLeavesNoPhantomReminder() {
        Instant now = Instant.now();
        TaskDto task = taskService.create(new CreateTaskRequest(
                "将被直接删除的任务", null, null, now.plus(30, ChronoUnit.MINUTES), null));

        scanner.scan(now);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.TASK, task.id()))
                .isEqualTo(1);

        // 直接走仓储删除：模拟「不经过提醒模块清理」的删除路径。
        // task 模块并不知道提醒的存在，也不该知道 —— 所以这里刻意不调用任何清理。
        taskRepository.deleteById(task.id());

        ReminderScanResult result = scanner.scan(now.plus(1, ChronoUnit.MINUTES));

        assertThat(result.pruned()).as("应清理掉 1 条孤儿提醒").isEqualTo(1);
        assertThat(reminderRepository.countBySourceTypeAndSourceId(ReminderSource.TASK, task.id()))
                .isZero();
        assertThat(reminderService.list(null, true, null))
                .as("不应再向用户展示指向已删任务的提醒")
                .isEmpty();
    }

    // ------------------------------------------------------------------ Agent 工具

    @Test
    @DisplayName("对话中创建日程：数据库真实新增 1 行")
    void agentCreatesRealEvent() throws Exception {
        model.enqueueToolCall("createCalendarEvent",
                "{\"title\":\"明天三点开会\",\"startsAt\":\"2026-12-01T15:00:00Z\",\"endsAt\":\"2026-12-01T16:00:00Z\"}");
        model.enqueueText("已创建日程");
        String conversationId = createChatSession();

        List<SseEvent> events = stream(conversationId, "帮我记一下明天三点开会");

        assertThat(events).extracting(SseEvent::name).contains("done");
        assertThat(eventRepository.count()).isEqualTo(1);
        assertThat(eventRepository.findAll().get(0).getTitle()).isEqualTo("明天三点开会");
    }

    @Test
    @DisplayName("对话中删除日程：只生成待确认，日程仍在；确认后才真正删除")
    void agentDeleteRequiresHumanConfirmation() throws Exception {
        UUID eventId = createEvent("要经确认才删的会", Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(2, ChronoUnit.HOURS), 15);

        model.enqueueToolCall("deleteCalendarEvent", "{\"eventIds\":[\"" + eventId + "\"]}");
        model.enqueueText("需要你在界面上确认");
        String conversationId = createChatSession();

        stream(conversationId, "把那个会删掉");

        // 核心断言：模型调用了删除工具，但数据一行没少
        assertThat(eventRepository.existsById(eventId)).isTrue();

        var pending = confirmationRepository.findByStatusOrderByCreatedAtDesc(ConfirmationStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getAction().name()).isEqualTo("DELETE_CALENDAR_EVENT");
        assertThat(pending.get(0).getSummary()).contains("要经确认才删的会");

        // 人工确认后才真正删除
        var execution = confirmationService.confirm(pending.get(0).getId(), pending.get(0).getToken());
        assertThat(execution.executed()).isTrue();
        assertThat(eventRepository.existsById(eventId)).isFalse();
    }

    // ------------------------------------------------------------------ 工具

    private UUID createEvent(String title, Instant startsAt, Instant endsAt, int leadMinutes) {
        return eventService.create(new CreateEventRequest(title, null, null, false,
                startsAt, endsAt, RecurrenceType.NONE, null, leadMinutes)).id();
    }

    private String createChatSession() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/chat/sessions", "{}");
        assertThat(response.status()).isEqualTo(201);
        Matcher matcher = Pattern.compile("\"conversationId\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private List<SseEvent> stream(String conversationId, String content) throws Exception {
        return http.sse("/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"" + content + "\"}", Map.of());
    }
}
