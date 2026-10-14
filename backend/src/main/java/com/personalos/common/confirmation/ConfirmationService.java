package com.personalos.common.confirmation;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
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
 * 待确认操作的**通用**生命周期管理。
 *
 * <h2>本类与业务无关</h2>
 * 它只负责四件与具体动作无关的事：令牌、有效期、单次性、原子占位。
 * 「确认之后干什么」由 {@link ConfirmationRegistry} 路由到各业务模块的
 * {@link ConfirmationActionHandler}，因此本类**不需要**知道任务、日程这些概念。
 *
 * <h2>安全模型</h2>
 * 本服务**不向模型暴露任何入口**。模型只能通过 Agent 工具创建待确认记录；
 * 而「确认」这个动作只存在于本服务与其控制器中，工具集合里没有对应能力。
 * 因此防线是**结构性的** —— 即使模型被提示注入说服，它也没有可调用的东西去完成危险操作。
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

    /** 待确认操作的有效期 */
    private static final Duration VALIDITY = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConfirmationRepository repository;
    private final ConfirmationRegistry registry;
    private final JsonCodec jsonCodec;

    public ConfirmationService(ConfirmationRepository repository,
                               ConfirmationRegistry registry,
                               JsonCodec jsonCodec) {
        this.repository = repository;
        this.registry = registry;
        this.jsonCodec = jsonCodec;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 创建一个待确认事项。
     *
     * <p><b>summary 必须由服务端依据 payload 生成</b>，不采信模型输出的文本 ——
     * 否则模型可以写一个「删除 1 个任务：不重要的临时文件」的描述，而 payload 里却是别的任务。
     *
     * @param action         动作类型
     * @param summary        面向人类的可读描述（由调用方依据 payload 生成）
     * @param payload        执行所需参数，会被序列化后原样回放
     * @param affectedCount  预计影响行数，供用户判断风险
     * @param conversationId 发起会话，可空
     */
    @Transactional
    public ConfirmationDto create(ConfirmationAction action, String summary, Object payload,
                                 int affectedCount, String conversationId) {
        Instant now = Instant.now();
        ConfirmationEntity entity = new ConfirmationEntity(
                randomToken(), conversationId, action, summary, jsonCodec.toJson(payload),
                affectedCount, now, now.plus(VALIDITY));
        ConfirmationEntity saved = repository.save(entity);
        log.info("生成待确认操作 id={} action={} summary={}", saved.getId(), action, summary);
        return toDto(saved);
    }

    // ------------------------------------------------------------------ 查询

    @Transactional(readOnly = true)
    public List<ConfirmationDto> list(String conversationId, ConfirmationStatus status) {
        ConfirmationStatus effectiveStatus = status == null ? ConfirmationStatus.PENDING : status;
        List<ConfirmationEntity> found = (conversationId == null || conversationId.isBlank())
                ? repository.findByStatusOrderByCreatedAtDesc(effectiveStatus)
                : repository.findByStatusAndConversationIdOrderByCreatedAtDesc(effectiveStatus, conversationId);
        return found.stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------------ 决议

    /**
     * 确认并执行。
     *
     * @throws BusinessException 令牌不匹配、状态非待确认、或已过期（code 40002）
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public ConfirmationExecution confirm(UUID id, String token) {
        ConfirmationEntity entity = requireEntity(id);
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

        // 原子占位：并发确认时只有一个事务能更新到行。
        // 必须与下面的实际执行处于同一事务，否则执行失败时无法整体回滚，
        // 会留下「已确认但没执行」的矛盾状态。
        int claimed = repository.compareAndSetStatus(id, ConfirmationStatus.PENDING,
                ConfirmationStatus.CONFIRMED, now);
        if (claimed == 0) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已被处理");
        }

        try {
            int affected = registry.require(entity.getAction())
                    .execute(entity.getAction(), entity.getPayload());
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
        ConfirmationEntity entity = requireEntity(id);
        requireToken(entity, token);

        int updated = repository.compareAndSetStatus(id, ConfirmationStatus.PENDING,
                ConfirmationStatus.CANCELLED, Instant.now());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已失效或已被处理");
        }
        log.info("取消待确认操作 id={}", id);
    }

    // ------------------------------------------------------------------ 内部

    private ConfirmationEntity requireEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("待确认操作不存在：" + id));
    }

    private void requireToken(ConfirmationEntity entity, String token) {
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

    private ConfirmationDto toDto(ConfirmationEntity entity) {
        return new ConfirmationDto(entity.getId(), entity.getToken(), entity.getAction().name(),
                entity.getSummary(), entity.getAffectedCount(), entity.getStatus().name(),
                entity.getExpiresAt(), entity.getCreatedAt());
    }
}
