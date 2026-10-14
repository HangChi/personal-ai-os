package com.personalos.task.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.confirmation.ConfirmationAction;
import com.personalos.common.confirmation.ConfirmationEntity;
import com.personalos.common.confirmation.ConfirmationExecution;
import com.personalos.common.confirmation.ConfirmationRepository;
import com.personalos.common.confirmation.ConfirmationService;
import com.personalos.common.confirmation.ConfirmationStatus;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.task.dto.CreateTaskRequest;
import com.personalos.task.dto.TaskDto;
import com.personalos.task.entity.TaskPriority;
import com.personalos.task.repository.TaskRepository;
import com.personalos.task.service.TaskService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 待确认操作的生命周期与失效语义（FR-010/011，SC-008）。
 *
 * <p>这里刻意覆盖「过期、已被使用、令牌不匹配、目标已不存在」四种失败路径 ——
 * 它们共同构成「模型无法绕过人类确认」这条不变量的兜底。
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none"})
@Testcontainers
class ConfirmationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    ConfirmationService confirmationService;
    @Autowired
    TaskConfirmationService taskConfirmationService;
    @Autowired
    ConfirmationRepository confirmationRepository;
    @Autowired
    TaskService taskService;
    @Autowired
    TaskRepository taskRepository;

    @BeforeEach
    void cleanUp() {
        confirmationRepository.deleteAll();
        taskRepository.deleteAll();
    }

    @Test
    @DisplayName("令牌不匹配 → 拒绝，且目标数据不受影响")
    void wrongTokenIsRejected() {
        TaskDto task = taskService.create(new CreateTaskRequest("受保护的任务", null, null, null));
        var confirmation = taskConfirmationService.requestDeleteTasks(
                List.of(task.id()), List.of(task.title()), "conv-1");

        assertThatThrownBy(() -> confirmationService.confirm(confirmation.id(), "wrong-token"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFIRMATION_NOT_ACTIVE);

        assertThat(taskRepository.existsById(task.id())).isTrue();
        assertThat(confirmationRepository.findById(confirmation.id()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.PENDING);
    }

    @Test
    @DisplayName("单次性：确认后再确认 → 拒绝，且不会二次执行")
    void confirmationIsSingleUse() {
        TaskDto task = taskService.create(new CreateTaskRequest("只删一次", null, null, null));
        var confirmation = taskConfirmationService.requestDeleteTasks(
                List.of(task.id()), List.of(task.title()), "conv-1");

        ConfirmationExecution first = confirmationService.confirm(confirmation.id(), confirmation.token());
        assertThat(first.executed()).isTrue();
        assertThat(taskRepository.existsById(task.id())).isFalse();

        assertThatThrownBy(() -> confirmationService.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONFIRMATION_NOT_ACTIVE);
    }

    @Test
    @DisplayName("过期 → 拒绝，并把记录置为已过期（SC-008）")
    void expiredConfirmationIsRejected() {
        ConfirmationEntity expired = confirmationRepository.save(new ConfirmationEntity(
                "expired-token", "conv-1", ConfirmationAction.DELETE_TASK, "过期测试",
                "{\"taskIds\":[],\"titles\":[]}", 1,
                Instant.now().minusSeconds(1800), Instant.now().minusSeconds(60)));

        assertThatThrownBy(() -> confirmationService.confirm(expired.getId(), "expired-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");

        // noRollbackFor 让过期标记得以保留，而不会被异常回滚掉
        assertThat(confirmationRepository.findById(expired.getId()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.EXPIRED);
    }

    @Test
    @DisplayName("取消之后不可再确认")
    void cancelledConfirmationCannotBeConfirmed() {
        TaskDto task = taskService.create(new CreateTaskRequest("先取消", null, null, null));
        var confirmation = taskConfirmationService.requestDeleteTasks(
                List.of(task.id()), List.of(task.title()), "conv-1");

        confirmationService.cancel(confirmation.id(), confirmation.token());

        assertThat(confirmationRepository.findById(confirmation.id()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.CANCELLED);
        assertThatThrownBy(() -> confirmationService.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class);
        assertThat(taskRepository.existsById(task.id())).isTrue();
    }

    @Test
    @DisplayName("确认时目标已被删除 → 优雅返回，不报 500，记录置为已取消")
    void missingTargetIsHandledGracefully() {
        TaskDto task = taskService.create(new CreateTaskRequest("稍后会被删掉", null, null, null));
        var confirmation = taskConfirmationService.requestDeleteTasks(
                List.of(task.id()), List.of(task.title()), "conv-1");

        // 模拟：确认之前目标已经不在了
        taskService.delete(task.id());

        ConfirmationExecution execution =
                confirmationService.confirm(confirmation.id(), confirmation.token());

        assertThat(execution.executed()).isFalse();
        assertThat(execution.affectedCount()).isZero();
        assertThat(execution.summary()).contains("目标已不存在");
        assertThat(confirmationRepository.findById(confirmation.id()).orElseThrow().getStatus())
                .isEqualTo(ConfirmationStatus.CANCELLED);
    }

    @Test
    @DisplayName("关键字段确认：截止时间与优先级在确认后才生效")
    void scheduleAndPriorityApplyOnlyAfterConfirmation() {
        TaskDto task = taskService.create(new CreateTaskRequest(
                "关键字段任务", null, TaskPriority.LOW, null));
        Instant newDueAt = Instant.parse("2026-12-01T09:00:00Z");

        var scheduleConfirmation = taskConfirmationService.requestScheduleTask(
                task.id(), task.title(), newDueAt, "conv-1");
        var priorityConfirmation = taskConfirmationService.requestSetPriority(
                task.id(), task.title(), TaskPriority.HIGH, "conv-1");

        // 确认前：两项都没变
        var before = taskRepository.findById(task.id()).orElseThrow();
        assertThat(before.getDueAt()).isNull();
        assertThat(before.getPriority()).isEqualTo(TaskPriority.LOW);

        confirmationService.confirm(scheduleConfirmation.id(), scheduleConfirmation.token());
        confirmationService.confirm(priorityConfirmation.id(), priorityConfirmation.token());

        var after = taskRepository.findById(task.id()).orElseThrow();
        assertThat(after.getDueAt()).isEqualTo(newDueAt);
        assertThat(after.getPriority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    @DisplayName("待确认列表：可按会话过滤，且 summary 由服务端生成（含目标标题）")
    void listFiltersByConversationAndStatus() {
        TaskDto task = taskService.create(new CreateTaskRequest("列表演示", null, null, null));
        taskConfirmationService.requestDeleteTasks(List.of(task.id()), List.of(task.title()), "conv-A");
        taskConfirmationService.requestDeleteTasks(List.of(task.id()), List.of(task.title()), "conv-B");

        assertThat(confirmationService.list("conv-A", ConfirmationStatus.PENDING)).hasSize(1);
        assertThat(confirmationService.list(null, ConfirmationStatus.PENDING)).hasSize(2);
        assertThat(confirmationService.list("conv-A", ConfirmationStatus.PENDING).get(0).summary())
                .contains("列表演示");
    }

    @Test
    @DisplayName("确认不存在的记录 → 资源不存在")
    void unknownConfirmationRaisesNotFound() {
        assertThatThrownBy(() -> confirmationService.confirm(UUID.randomUUID(), "any"))
                .hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("并发确认同一记录：只有一个能进入执行（compare-and-set）")
    void onlyOneConcurrentConfirmWins() {
        TaskDto task = taskService.create(new CreateTaskRequest("并发确认", null, null, null));
        var confirmation = taskConfirmationService.requestDeleteTasks(
                List.of(task.id()), List.of(task.title()), "conv-1");

        // 先手工把状态改成已确认，模拟「另一个请求已经赢了」。
        // 用实体持久化而不是 @Modifying 的 compareAndSetStatus —— 后者要求调用方自带事务，
        // 而这里只是想造出一个「已被处理」的状态。
        ConfirmationEntity alreadyResolved = confirmationRepository.findById(confirmation.id()).orElseThrow();
        alreadyResolved.markResolved(ConfirmationStatus.CONFIRMED, Instant.now());
        confirmationRepository.save(alreadyResolved);

        assertThatThrownBy(() -> confirmationService.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class);
        assertThat(taskRepository.existsById(task.id())).isTrue();
    }
}
