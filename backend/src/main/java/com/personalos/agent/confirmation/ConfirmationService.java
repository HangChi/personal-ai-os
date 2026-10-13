package com.personalos.agent.confirmation;

import com.personalos.agent.confirmation.ConfirmationPayloads.DeleteTasks;
import com.personalos.agent.confirmation.ConfirmationPayloads.ScheduleTask;
import com.personalos.agent.confirmation.ConfirmationPayloads.SetPriority;
import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import com.personalos.task.service.TaskService;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 待确认操作的生命周期管理。
 *
 * <h2>安全模型</h2>
 * 本服务**不向模型暴露任何入口**。模型只能通过 Agent 工具创建待确认记录；
 * 而「确认」这个动作只存在于本服务与其控制器中，工具集合里没有对应能力（FR-013 / SC-003）。
 * 因此防线是**结构性的** —— 即使模型被提示注入说服，它也没有可调用的东西去完成删除。
 *
 * <h2>为什么确认方法允许业务异常回滚</h2>
 * 过期分支需要把记录标记为 {@code EXPIRED} 后再抛错告知用户。若异常触发事务回滚，
 * 那次标记就丢了。故用 {@code noRollbackFor} 让业务异常不回滚 —— 标记得以保留，
 * 用户依然收到明确拒绝。非业务异常（真正的系统故障）仍会正常回滚，
 * 从而保证「记录状态」与「实际执行」要么一起成功、要么一起不发生。
 */
@Service
public class ConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

    /** 待确认操作的有效期（FR-011） */
    private static final Duration VALIDITY = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TaskConfirmationRepository repository;
    private final TaskService taskService;
    private final JsonCodec jsonCodec;

    public ConfirmationService(TaskConfirmationRepository repository,
                               TaskService taskService,
                               JsonCodec jsonCodec) {
        this.repository = repository;
        this.taskService = taskService;
        this.jsonCodec = jsonCodec;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建「删除任务」的待确认操作。
     *
     * @param taskIds        目标任务
     * @param titles         标题快照（任务删除后仍可审计删了什么）
     * @param conversationId 发起会话，可空
     */
    @Transactional
    public TaskConfirmationDto requestDeleteTasks(List<UUID> taskIds, List<String> titles, String conversationId) {
        String summary = titles.size() == 1
                ? "删除任务：" + titles.get(0)
                : "删除 " + titles.size() + " 个任务：" + String.join("、", titles);
        return create(ConfirmationAction.DELETE_TASK, summary, new DeleteTasks(taskIds, titles),
                taskIds.size(), conversationId);
    }

    /**
     * 创建「修改截止时间」的待确认操作。
     */
    @Transactional
    public TaskConfirmationDto requestScheduleTask(UUID taskId, String title, Instant dueAt, String conversationId) {
        String summary = dueAt == null
                ? "清除任务「" + title + "」的截止时间"
                : "把任务「" + title + "」的截止时间改为 " + dueAt;
        return create(ConfirmationAction.SCHEDULE_TASK, summary, new ScheduleTask(taskId, title, dueAt),
                1, conversationId);
    }

    /**
     * 创建「修改优先级」的待确认操作。
     */
    @Transactional
    public TaskConfirmationDto requestSetPriority(UUID taskId, String title,
                                                  com.personalos.task.entity.TaskPriority priority,
                                                  String conversationId) {
        String summary = "把任务「" + title + "」的优先级改为 " + priority;
        return create(ConfirmationAction.SET_PRIORITY, summary, new SetPriority(taskId, title, priority),
                1, conversationId);
    }

    private TaskConfirmationDto create(ConfirmationAction action, String summary, Object payload,
                                       int affectedCount, String conversationId) {
        Instant now = Instant.now();
        TaskConfirmationEntity entity = new TaskConfirmationEntity(
                randomToken(), conversationId, action, summary, jsonCodec.toJson(payload),
                affectedCount, now, now.plus(VALIDITY));
        TaskConfirmationEntity saved = repository.save(entity);
        log.info("生成待确认操作 id={} action={} summary={}", saved.getId(), action, summary);
        return toDto(saved);
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public List<TaskConfirmationDto> list(String conversationId, ConfirmationStatus status) {
        ConfirmationStatus effectiveStatus = status == null ? ConfirmationStatus.PENDING : status;
        List<TaskConfirmationEntity> found = (conversationId == null || conversationId.isBlank())
                ? repository.findByStatusOrderByCreatedAtDesc(effectiveStatus)
                : repository.findByStatusAndConversationIdOrderByCreatedAtDesc(effectiveStatus, conversationId);
        return found.stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------------ 决议

    /**
     * 确认并执行。
     *
     * @param id    记录标识
     * @param token 一次性令牌
     * @return 执行结果
     * @throws BusinessException 令牌不匹配、状态非待确认、或已过期（code 40002）
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ConfirmationExecution confirm(UUID id, String token) {
        TaskConfirmationEntity entity = requireEntity(id);
        requireToken(entity, token);

        if (entity.getStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE,
                    "该确认已" + describeStatus(entity.getStatus()) + "，无法再次执行");
        }

        Instant now = Instant.now();
        if (entity.getExpiresAt().isBefore(now)) {
            entity.markResolved(ConfirmationStatus.EXPIRED, now);
            repository.save(entity);
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已过期，请重新发起");
        }

        // 原子占位：并发确认时只有一个事务能更新到行
        int claimed = repository.compareAndSetStatus(id, ConfirmationStatus.PENDING,
                ConfirmationStatus.CONFIRMED, now);
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已被处理");
        }

        try {
            int affected = applyPayload(entity);
            log.info("已执行待确认操作 id={} action={} affected={}", id, entity.getAction(), affected);
            return new ConfirmationExecution(true, affected, "已执行：" + entity.getSummary());
        } catch (ResourceNotFoundException ex) {
            // 目标在确认前已被删除：置为已取消并给出可读提示，不能让用户看到 500
            repository.compareAndSetStatus(id, ConfirmationStatus.CONFIRMED,
                    ConfirmationStatus.CANCELLED, now);
            log.warn("待确认操作的目标已不存在 id={} action={}", id, entity.getAction());
            return new ConfirmationExecution(false, 0, "目标已不存在，未执行任何变更");
        }
    }

    /**
     * 取消。
     */
    @Transactional
    public void cancel(UUID id, String token) {
        TaskConfirmationEntity entity = requireEntity(id);
        requireToken(entity, token);

        int updated = repository.compareAndSetStatus(id, ConfirmationStatus.PENDING,
                ConfirmationStatus.CANCELLED, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已失效或已被处理");
        }
        log.info("取消待确认操作 id={}", id);
    }

    // ------------------------------------------------------------------ 内部

    private int applyPayload(TaskConfirmationEntity entity) {
        return switch (entity.getAction()) {
            case DELETE_TASK -> {
                DeleteTasks payload = jsonCodec.fromJson(entity.getPayload(), DeleteTasks.class);
                int affected = taskService.deleteAll(payload.taskIds());
                if (affected == 0) {
                    // 目标任务在确认之前已被删除：这不是「执行成功但没影响任何行」，
                    // 而是「已经没有可执行的目标」。区分二者，用户才知道到底发生了什么
                    throw new ResourceNotFoundException("目标任务已不存在");
                }
                yield affected;
            }
            case SCHEDULE_TASK -> {
                ScheduleTask payload = jsonCodec.fromJson(entity.getPayload(), ScheduleTask.class);
                taskService.reschedule(payload.taskId(), payload.dueAt());
                yield 1;
            }
            case SET_PRIORITY -> {
                SetPriority payload = jsonCodec.fromJson(entity.getPayload(), SetPriority.class);
                taskService.changePriority(payload.taskId(), payload.priority());
                yield 1;
            }
        };
    }

    private TaskConfirmationEntity requireEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("待确认操作不存在：" + id));
    }

    private void requireToken(TaskConfirmationEntity entity, String token) {
        if (token == null || !entity.getToken().equals(token.strip())) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "确认令牌不匹配");
        }
    }

    private String describeStatus(ConfirmationStatus status) {
        return switch (status) {
            case PENDING -> "待确认";
            case CONFIRMED -> "执行";
            case CANCELLED -> "取消";
            case EXPIRED -> "过期";
        };
    }

    private String randomToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private TaskConfirmationDto toDto(TaskConfirmationEntity entity) {
        return new TaskConfirmationDto(entity.getId(), entity.getToken(), entity.getAction().name(),
                entity.getSummary(), entity.getAffectedCount(), entity.getStatus().name(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
