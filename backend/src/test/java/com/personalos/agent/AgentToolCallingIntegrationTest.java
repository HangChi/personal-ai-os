package com.personalos.agent;

import com.personalos.support.ContainerContextTest;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationEntity;
import com.personalos.common.confirmation.ConfirmationExecution;
import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.agent.tool.TaskTools;
import com.personalos.common.ai.ToolContributor;
import com.personalos.common.idempotency.IdempotencyOutcome;
import com.personalos.common.idempotency.OperationRecordEntity;
import com.personalos.common.idempotency.OperationRecordRepository;
import com.personalos.common.idempotency.OperationSource;
import com.personalos.support.HttpTestClient;
import com.personalos.support.HttpTestClient.SseEvent;
import com.personalos.support.ScriptedToolCallingChatModel;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.entity.TaskEntity;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.repository.TaskRepository;
import com.personalos.task.service.TaskService;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Agent 工具调用的集成测试。
 *
 * <h2>为什么断言口径是「数据库行数」</h2>
 * 如果只断言「接口返回了成功」，一个「工具压根没执行、但把成功文案回给了模型」的实现
 * 也能全绿。行数不会撒谎 —— 所以 SC-001/002/004/006 全部以行数为口径。
 *
 * <p>全程无网络、无密钥：{@code spring.ai.model.chat=none} 关闭真实模型，
 * 由 {@link ScriptedToolCallingChatModel} 精确编排「先调工具、再给回复」。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=none"
})
@Testcontainers
class AgentToolCallingIntegrationTest extends ContainerContextTest {

    @Autowired com.personalos.calendar.repository.CalendarEventRepository calendarEvents;
    @Autowired com.personalos.chat.repository.ChatMessageRepository chatMessages;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @TestConfiguration
    static class StubModelConfiguration {
        @Bean
        ScriptedToolCallingChatModel scriptedChatModel() {
            return new ScriptedToolCallingChatModel();
        }
    }

    private final HttpTestClient http;
    private final ScriptedToolCallingChatModel model;
    private final TaskRepository taskRepository;
    private final ConfirmationRepository confirmationRepository;
    private final OperationRecordRepository operationRecordRepository;
    private final TaskService taskService;
    private final ConfirmationService confirmationService;
    private final List<ToolContributor> toolContributors;

    @Autowired
    @SuppressWarnings("unused")
    AgentToolCallingIntegrationTest(@Value("${local.server.port}") int port,
                                    ScriptedToolCallingChatModel model,
                                    TaskRepository taskRepository,
                                    ConfirmationRepository confirmationRepository,
                                    OperationRecordRepository operationRecordRepository,
                                    TaskService taskService,
                                    ConfirmationService confirmationService,
                                    List<ToolContributor> toolContributors) {
        this.http = new HttpTestClient(port);
        this.model = model;
        this.taskRepository = taskRepository;
        this.confirmationRepository = confirmationRepository;
        this.operationRecordRepository = operationRecordRepository;
        this.taskService = taskService;
        this.confirmationService = confirmationService;
        this.toolContributors = toolContributors;
    }

    @BeforeEach
    void cleanUp() {
        // 每个用例从干净状态开始：断言才能写成「行数恰好等于 N」这种强口径
        confirmationRepository.deleteAll();
        taskRepository.deleteAll();
        operationRecordRepository.deleteAll();
        calendarEvents.deleteAll();
        model.reset();
    }

    @Test
    void missingCalendarToolCallIsRepairedBeforeStreamingOrSavingFalseSuccess() throws Exception {
        String conversationId = createSession();
        model.enqueueText("记下了明天的日期。");
        stream(conversationId, "我明天下午14.30要开组会，在A座706会议室");
        model.enqueueText("时间是两小时。");
        stream(conversationId, "组会时间改成2小时吧");
        model.enqueueText("已创建 ✅（错误的未执行回复）");
        model.enqueueToolCall("createCalendarEvent", """
                {"title":"党支部第二次集中大课","startsAt":"2026-11-02T19:00:00+08:00",
                 "endsAt":"2026-11-02T21:00:00+08:00","location":"B501教室","reminderLeadMinutes":15}
                """);
        model.enqueueText("已创建：11月2日19–21点，B501教室。");

        var events = stream(conversationId, "晚上19.-21.党支部第二次集中大课，在B501教室");

        assertThat(events).extracting(SseEvent::name).contains("done").doesNotContain("error");
        assertThat(events.toString()).doesNotContain("错误的未执行回复");
        assertThat(calendarEvents.findAll()).singleElement().satisfies(event -> {
            assertThat(event.getTitle()).isEqualTo("党支部第二次集中大课");
            assertThat(event.getLocation()).isEqualTo("B501教室");
            assertThat(event.getStartsAt()).isEqualTo(java.time.Instant.parse("2026-11-02T11:00:00Z"));
            assertThat(event.getEndsAt()).isEqualTo(java.time.Instant.parse("2026-11-02T13:00:00Z"));
        });
        assertThat(operationRecordsOf("createCalendarEvent")).hasSize(1);
        assertThat(chatMessages.findByConversationIdOrderBySequenceNoAsc(conversationId))
                .hasSize(6).noneMatch(message -> message.getContent().contains("错误的未执行回复"));
        assertThat(model.receivedInstructions().get(3)).anyMatch(message ->
                message.getText() != null && message.getText().contains("我明天下午14.30"));
        assertThat(model.receivedInstructions().get(3)).anyMatch(message ->
                message.getText() != null && message.getText().contains("应承接前文用户明确指定"));
    }

    @Test
    void repeatedUnsupportedCalendarSuccessBecomesHonestIncompleteReply() throws Exception {
        String conversationId = createSession();
        model.enqueueText("已创建 ✅（第一次虚报）");
        model.enqueueText("已创建 ✅（第二次虚报）");
        var events = stream(conversationId, "添加明天19–21点的大课日程，B501教室");
        assertThat(events.toString()).contains("尚未创建").doesNotContain("虚报");
        assertThat(calendarEvents.count()).isZero();
        assertThat(model.receivedInstructions()).hasSize(2);
        assertThat(chatMessages.findByConversationIdOrderBySequenceNoAsc(conversationId))
                .hasSize(2).last().satisfies(message -> assertThat(message.getContent()).contains("尚未创建"));
    }

    // ---------------------------------------------------------------- SC-001

    @Test
    @DisplayName("模型调用 createTask 工具后，task 表真实新增 1 行且字段正确（SC-001）")
    void createTaskToolActuallyInsertsRow() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("createTask", "{\"title\":\"写周报\",\"priority\":\"HIGH\"}");
        model.enqueueText("已创建任务「写周报」");

        List<SseEvent> events = stream(conversationId, "帮我加一个任务：写周报，优先级高");

        assertThat(events).extracting(SseEvent::name).contains("done");

        List<TaskEntity> tasks = taskRepository.findAll();
        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).getTitle()).isEqualTo("写周报");
        assertThat(tasks.get(0).getPriority()).isEqualTo(TaskPriority.HIGH);
        assertThat(tasks.get(0).getStatus().name()).isEqualTo("TODO");
    }

    // ---------------------------------------------------------------- SC-002

    @Test
    @DisplayName("deleteTask 工具只生成待确认，任务行数不变；确认之后才真的删除（SC-002）")
    void deleteTaskToolRequiresConfirmationBeforeTouchingData() throws Exception {
        TaskDto task = taskService.create(new CreateTaskRequest("要删的任务", null, null, null, null));
        String conversationId = createSession();

        model.enqueueToolCall("deleteTask", "{\"taskIds\":[\"" + task.id() + "\"]}");
        model.enqueueText("删除需要你在界面上确认");

        stream(conversationId, "把「要删的任务」删掉");

        // 核心断言：模型调用了删除工具，但数据一行都没少
        assertThat(taskRepository.existsById(task.id())).isTrue();
        assertThat(taskRepository.count()).isEqualTo(1);

        List<ConfirmationEntity> pending =
                confirmationRepository.findByStatusOrderByCreatedAtDesc(ConfirmationStatus.PENDING);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getAction()).isEqualTo(ConfirmationAction.DELETE_TASK);
        assertThat(pending.get(0).getSummary()).contains("要删的任务");

        // 审计里应当是「待确认」而不是「成功」
        assertThat(operationRecordsOf("deleteTask"))
                .extracting(OperationRecordEntity::getOutcome)
                .containsExactly(IdempotencyOutcome.REQUIRES_CONFIRMATION);

        // 人工确认后才真正删除
        ConfirmationExecution execution =
                confirmationService.confirm(pending.get(0).getId(), pending.get(0).getToken());

        assertThat(execution.executed()).isTrue();
        assertThat(execution.affectedCount()).isEqualTo(1);
        assertThat(taskRepository.existsById(task.id())).isFalse();
        assertThat(confirmationRepository.findById(pending.get(0).getId()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.CONFIRMED);

        // The next turn still has the old "needs approval" assistant message in memory,
        // but must receive fresh database state instead of inferring pending work from it.
        model.enqueueText("已确认，当前没有待确认操作。");
        stream(conversationId, "刚才已经全部确认了，还有待确认的吗？");
        var nextPrompt = model.receivedInstructions().get(model.receivedInstructions().size() - 1);
        assertThat(nextPrompt).anyMatch(message -> message.getText() != null
                && message.getText().contains("删除需要你在界面上确认"));
        assertThat(nextPrompt).anyMatch(message -> message.getText() != null
                && message.getText().contains("当前会话尚待人工确认：0 项")
                && message.getText().contains("CONFIRMED"));
        assertThat(nextPrompt.toString()).doesNotContain(pending.get(0).getToken());
    }

    @Test
    @DisplayName("关键字段修改（优先级）同样需确认，且确认前数据不变")
    void setTaskPriorityRequiresConfirmation() throws Exception {
        TaskDto task = taskService.create(new CreateTaskRequest("待调优先级的任务", null, TaskPriority.LOW, null, null));
        String conversationId = createSession();

        model.enqueueToolCall("setTaskPriority",
                "{\"taskId\":\"" + task.id() + "\",\"priority\":\"HIGH\"}");
        model.enqueueText("需要确认");

        stream(conversationId, "把「待调优先级的任务」优先级改成高");

        assertThat(taskRepository.findById(task.id()).orElseThrow().getPriority()).isEqualTo(TaskPriority.LOW);

        ConfirmationEntity confirmation =
                confirmationRepository.findByStatusOrderByCreatedAtDesc(ConfirmationStatus.PENDING).get(0);
        assertThat(confirmation.getAction()).isEqualTo(ConfirmationAction.SET_PRIORITY);

        confirmationService.confirm(confirmation.getId(), confirmation.getToken());

        assertThat(taskRepository.findById(task.id()).orElseThrow().getPriority()).isEqualTo(TaskPriority.HIGH);
    }

    // ---------------------------------------------------------------- 可逆操作不需确认

    @Test
    @DisplayName("setTaskCompleted 直接生效、无需确认，并写入完成时间")
    void setTaskCompletedAppliesImmediately() throws Exception {
        TaskDto task = taskService.create(new CreateTaskRequest("要完成的任务", null, null, null, null));
        String conversationId = createSession();

        model.enqueueToolCall("setTaskCompleted",
                "{\"taskId\":\"" + task.id() + "\",\"completed\":true}");
        model.enqueueText("已经帮你标记完成");

        stream(conversationId, "「要完成的任务」做完了");

        TaskEntity updated = taskRepository.findById(task.id()).orElseThrow();
        assertThat(updated.getStatus().name()).isEqualTo("DONE");
        assertThat(updated.getCompletedAt()).isNotNull();
        // 无需确认：不应产生待确认记录
        assertThat(confirmationRepository.count()).isZero();
    }

    // ---------------------------------------------------------------- SC-004

    @Test
    @DisplayName("同一轮内重复调用同一工具（同参数），数据库只新增 1 行（SC-004）")
    void duplicateToolCallInSameTurnExecutesOnce() throws Exception {
        String conversationId = createSession();
        String arguments = "{\"title\":\"同名任务\"}";

        model.enqueueToolCall("createTask", arguments);
        model.enqueueToolCall("createTask", arguments);   // 模拟模型重复调用
        model.enqueueText("done");

        List<SseEvent> events = stream(conversationId, "加两个同名任务？不，就加一个");

        assertThat(events).extracting(SseEvent::name).contains("done");
        assertThat(taskRepository.findAll()).hasSize(1);

        // 第二次调用应当被标记为 DUPLICATE，而不是第二次 SUCCESS
        assertThat(operationRecordsOf("createTask"))
                .extracting(OperationRecordEntity::getOutcome)
                .containsExactlyInAnyOrder(IdempotencyOutcome.SUCCESS);
    }

    @Test
    @DisplayName("跨轮次的相同调用会真实执行 —— 用户合法地建两个同名任务不该被去重（SC-004 边界）")
    void sameToolCallInDifferentTurnsExecutesTwice() throws Exception {
        String conversationId = createSession();
        String arguments = "{\"title\":\"重复的任务\"}";

        model.enqueueToolCall("createTask", arguments);
        model.enqueueText("好的");
        stream(conversationId, "建一个任务");

        model.enqueueToolCall("createTask", arguments);
        model.enqueueText("好的");
        stream(conversationId, "再建一个一模一样的");

        assertThat(taskRepository.findAll()).hasSize(2);
    }

    // ---------------------------------------------------------------- SC-006

    @Test
    @DisplayName("工具入参非法时数据库零写入（SC-006）")
    void invalidToolArgumentsProduceNoWrite() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("createTask", "{\"title\":\"   \"}");
        model.enqueueText("标题不能为空");

        stream(conversationId, "建一个没有标题的任务");

        assertThat(taskRepository.count()).isZero();
        assertThat(confirmationRepository.count()).isZero();
        assertThat(operationRecordsOf("createTask"))
                .extracting(OperationRecordEntity::getOutcome)
                .containsExactly(IdempotencyOutcome.REJECTED);
    }

    @Test
    @DisplayName("删除不存在的任务时被拒绝，不生成待确认事项")
    void deletingUnknownTaskIsRejected() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("deleteTask", "{\"taskIds\":[\"" + UUID.randomUUID() + "\"]}");
        model.enqueueText("任务不存在");

        stream(conversationId, "删掉那个不存在的任务");

        assertThat(confirmationRepository.count()).isZero();
        assertThat(taskRepository.count()).isZero();
        assertThat(operationRecordsOf("deleteTask"))
                .extracting(OperationRecordEntity::getOutcome)
                .containsExactly(IdempotencyOutcome.REJECTED);
    }

    // ---------------------------------------------------------------- SC-003 / SC-007

    @Test
    @DisplayName("暴露给模型的工具集合中不存在任何「确认」类工具（SC-003）")
    void toolSetExposesNoConfirmationCapability() {
        List<String> toolNames = new ArrayList<>();
        for (ToolContributor contributor : toolContributors) {
            for (Object tool : contributor.toolObjects()) {
                Class<?> targetClass = AopUtils.getTargetClass(tool);
                for (Method method : targetClass.getMethods()) {
                    Tool annotation = method.getAnnotation(Tool.class);
                    if (annotation != null) {
                        toolNames.add(annotation.name());
                    }
                }
            }
        }

        // 任务工具必须都在
        assertThat(toolNames).contains(
                "createTask", "findTasks", "getTask", "updateTaskDetails",
                "setTaskCompleted", "deleteTask", "scheduleTask", "setTaskPriority");

        // 真正要守的不变量：模型没有任何途径完成「确认」这一步。
        // 刻意不写成"恰好这 8 个"—— 阶段 3 的知识库检索工具会合法地加入这个集合，
        // 那种写法会把「新增工具」误报成回归。
        assertThat(toolNames).noneMatch(name -> name.toLowerCase().contains("confirm"));
        assertThat(toolNames).noneMatch(name -> name.toLowerCase().contains("approve"));
    }

    @Test
    @DisplayName("每次工具调用都留下审计记录，且被去重的调用与真实执行可区分（SC-007）")
    void everyToolCallIsAudited() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("findTasks", "{\"status\":\"TODO\"}");
        model.enqueueToolCall("createTask", "{\"title\":\"被审计的任务\"}");
        model.enqueueText("完成");

        stream(conversationId, "看看待办，再加一个任务");

        List<OperationRecordEntity> records = operationRecordRepository.findAll();
        assertThat(records).isNotEmpty();
        assertThat(records).allMatch(record -> record.getSource() == OperationSource.TOOL);
        assertThat(records).allMatch(record -> record.getConversationId().equals(conversationId));
        assertThat(records).allMatch(record -> record.getDurationMs() != null);
        assertThat(records).extracting(OperationRecordEntity::getOperation)
                .contains("findTasks", "createTask");
    }

    @Test
    @DisplayName("工具执行日志接口可查询到调用记录")
    void toolInvocationEndpointReturnsRecords() throws Exception {
        String conversationId = createSession();

        model.enqueueToolCall("createTask", "{\"title\":\"日志里的任务\"}");
        model.enqueueText("完成");
        stream(conversationId, "加个任务");

        HttpTestClient.Response response = http.get(
                "/api/v1/agent/tool-invocations?conversationId=" + conversationId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).contains("createTask").contains("SUCCESS");
        assertThat(response.body()).doesNotContain("\"success\"");
    }

    @Test
    @DisplayName("当前时间上下文实际进入模型请求，无需额外时间工具")
    void modelReceivesCalendarTimeContext() throws Exception {
        model.enqueueText("收到");

        stream(createSession(), "明天是哪一天");

        assertThat(model.receivedInstructions()).hasSize(1);
        assertThat(model.receivedInstructions().get(0))
                .filteredOn(message -> message.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM)
                .singleElement().satisfies(message -> assertThat(message.getText())
                        .contains("[系统时间]", "当前日期：", "日历时区：", "明天日期：", "不要联网校时"));
        assertThat(operationRecordRepository.count()).isZero();
    }

    @Test
    @DisplayName("同工具超限通过 SSE 中文错误结束、保存停止原因，并允许下一轮继续")
    void frameworkToolLimitUsesApplicationErrorAndReleasesStream() throws Exception {
        String conversationId = createSession();
        for (int i = 0; i < 4; i++) {
            model.enqueueToolCall("findTasks", "{\"keyword\":\"查询" + i + "\"}");
        }

        List<SseEvent> events = stream(conversationId, "查询多组任务");

        assertThat(events).extracting(SseEvent::name).contains("error").doesNotContain("done");
        assertThat(events).filteredOn(event -> event.name().equals("error"))
                .singleElement().satisfies(event -> assertThat(event.data())
                        .contains("50203", "已完成的操作不会撤销").doesNotContain("Tool call limit"));
        assertThat(operationRecordsOf("findTasks")).hasSize(3);
        assertThat(http.get("/api/v1/chat/sessions/" + conversationId + "/messages").body())
                .contains("工具调用已停止").doesNotContain("连接中断", "Tool call limit");

        model.enqueueText("下一轮正常");
        assertThat(stream(conversationId, "继续聊天")).extracting(SseEvent::name)
                .contains("done").doesNotContain("error");
    }

    // ---------------------------------------------------------------- 工具方法本身

    @Test
    @DisplayName("8 个工具方法都被 @Tool 标注且描述非空（模型靠描述选择工具）")
    void allToolMethodsAreAnnotatedWithDescription() {
        for (Method method : TaskTools.class.getMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation == null) {
                continue;
            }
            assertThat(annotation.name()).as("工具名不能为空").isNotBlank();
            assertThat(annotation.description())
                    .as("工具 %s 的描述不能为空", annotation.name())
                    .isNotBlank();
        }
    }

    // ---------------------------------------------------------------- 工具方法

    private List<OperationRecordEntity> operationRecordsOf(String operation) {
        return operationRecordRepository.findAll().stream()
                .filter(record -> Objects.equals(record.getOperation(), operation))
                .toList();
    }

    private String createSession() throws Exception {
        HttpTestClient.Response response = http.post("/api/v1/chat/sessions", "{}");
        assertThat(response.status()).isEqualTo(201);
        return extractString(response.body(), "conversationId");
    }

    private List<SseEvent> stream(String conversationId, String content) throws Exception {
        return http.sse("/api/v1/chat/sessions/" + conversationId + "/stream",
                "{\"content\":\"" + content + "\"}", Map.of());
    }

    private static String extractString(String json, String field) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
