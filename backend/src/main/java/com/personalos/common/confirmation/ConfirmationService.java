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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <h2>过期标记为什么不再需要 noRollbackFor</h2>
 * 过期分支需要把记录标记为 {@code EXPIRED} 后再抛错告知用户。若异常触发事务回滚，
 * 那次标记就丢了。早期因此给整个方法加了 {@code noRollbackFor}，副作用是把
 * 「占位」与「执行」绑进同一事务 —— 反而引入「失败被记成成功」与「降级变 500」
 * 两个缺陷。现在改为三段式（见 {@link #confirm}），过期标记天然落在独立事务里。
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

    /**
     * 显式事务模板，传播行为 {@code REQUIRES_NEW}。
     *
     * <p>只用于「占位」与「结果回写」这两段必须独立提交的短事务。用模板而非注解，
     * 是因为这两个方法由同类内部调用，注解走不到 Spring 代理会**静默失效**。
     */
    private final TransactionTemplate requiresNew;

    public ConfirmationService(ConfirmationRepository repository,
                               ConfirmationRegistry registry,
                               JsonCodec jsonCodec,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.registry = registry;
        this.jsonCodec = jsonCodec;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * <h2>为什么占位与执行必须分成三段事务</h2>
     * 早期实现让「原子占位」与「执行 handler」共用同一个事务，并用
     * {@code @Transactional(noRollbackFor = BusinessException.class)} 保住过期标记。
     * 该注解作用于**整个方法**，于是两个方向都错了：
     * <ol>
     *   <li><b>失败被记成成功</b>：handler 抛业务异常时占位已经生效，
     *       记录停在 {@code CONFIRMED} —— 界面显示「已执行」，实际一行未改，
     *       令牌已一次性作废，用户连重试入口都没有，操作**永久丢失**。</li>
     *   <li><b>优雅降级变成 500</b>：想置 {@code CANCELLED} 的分支里，内层
     *       {@code @Transactional}（如 {@code TaskService.reschedule}）在共享事务上抛了
     *       运行时异常 → 事务被标 {@code rollbackOnly} → 提交时抛
     *       {@code UnexpectedRollbackException}，用户看到 500 而不是「目标已不存在」。</li>
     * </ol>
     * 两者其实是同一个结构问题：**占位是「事实记录」，执行是「副作用」，
     * 它们不该共享一个事务的成败。** 分段之后失败结果可以如实落库，
     * 也不存在共享事务被污染的问题。
     *
     * <h2>三段的具体边界</h2>
     * <ol>
     *   <li>{@link #claim}：校验 + 原子占位（{@code PENDING → CONFIRMED}），独立事务提交。
     *       并发时只有一个请求能占到，单次性由此保证。</li>
     *   <li>handler 执行：**事务外**。外部调用（MCP 走网络）不再占着数据库连接。</li>
     *   <li>{@link #recordOutcome}：独立事务把真实结果回写为
     *       {@code CONFIRMED} / {@code CANCELLED} / {@code FAILED}。</li>
     * </ol>
     * 崩溃窗口的取舍：若进程在第 1 段后、第 3 段前死掉，记录会停在 {@code CONFIRMED}。
     * 这与改前的最坏情况相同，但窗口从「handler 执行全程」缩到「两条 UPDATE 之间」，
     * 且不再由业务异常触发 —— 属于纯进程级故障。
     *
     * @throws BusinessException 令牌不匹配、状态非待确认、或已过期（code 40002）
     */
    public ConfirmationExecution confirm(UUID id, String token) {
        ConfirmationEntity claimed = claim(id, token);

        int affected;
        try {
            affected = registry.require(claimed.getAction())
                    .execute(claimed.getAction(), claimed.getPayload());
        } catch (ResourceNotFoundException ex) {
            // 目标在确认前已被删除：这不是系统故障，用户不需要重试
            recordOutcome(id, ConfirmationStatus.CANCELLED);
            log.warn("待确认操作的目标已不存在 id={} action={}", id, claimed.getAction());
            return new ConfirmationExecution(false, 0, "目标已不存在，未执行任何变更");
        } catch (RuntimeException ex) {
            // 系统没能执行（MCP server 挂掉、数据库约束冲突……）：
            // 必须如实记录为失败。此时令牌已作废，用户需要重新发起 ——
            // 若这里静默留在 CONFIRMED，用户会以为已经生效。
            recordOutcome(id, ConfirmationStatus.FAILED);
            log.warn("待确认操作执行失败 id={} action={}", id, claimed.getAction(), ex);
            throw ex;
        }

        recordOutcome(id, ConfirmationStatus.CONFIRMED);
        log.info("已执行待确认操作 id={} action={} affected={}", id, claimed.getAction(), affected);
        return new ConfirmationExecution(true, affected, "已执行：" + claimed.getSummary());
    }

    /**
     * 第一段：校验并原子占位（独立事务，方法返回即提交）。
     *
     * <p><b>为什么用 TransactionTemplate 而不是 @Transactional</b>：本方法由同一个类里的
     * {@link #confirm} 直接调用，走不到 Spring 代理，注解会**静默失效** ——
     * 事务消失后占位与执行又退回同一事务，本次重构就白做了。显式模板没有这个隐患，
     * 且把「事务边界在哪」写成看得见的东西（与 {@code ChatSummaryService} 同一手法）。
     *
     * <p><b>所有抛错都在事务回调之外</b>（刻意）：在 {@code execute} 的回调里抛异常会回滚
     * 那个事务，过期分支刚写的 {@code EXPIRED} 标记会被一起回滚掉 ——
     * 用户看到「已过期」，库里却还是 {@code PENDING}，两次确认得到两种说法。
     * 因此顺序固定为：先提交标记，再抛错。
     */
    private ConfirmationEntity claim(UUID id, String token) {
        // 独立只读查询（Spring Data 自带 readOnly 事务），回调外抛错不影响任何写入
        ConfirmationEntity entity = requireEntity(id);
        requireToken(entity, token);

        if (entity.getStatus() != ConfirmationStatus.PENDING) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE,
                    "该确认已" + describeStatus(entity.getStatus()) + "，无法再次执行");
        }

        Instant now = Instant.now();
        if (entity.getExpiresAt().isBefore(now)) {
            // 先独立提交 EXPIRED 标记，再抛错 —— 顺序反了标记就丢了
            requiresNew.executeWithoutResult(status -> repository.compareAndSetStatus(
                    id, ConfirmationStatus.PENDING, ConfirmationStatus.EXPIRED, now));
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已过期，请重新发起");
        }

        // 并发确认时只有一个事务能更新到行，另一个得到 0 —— 单次性由此保证
        Integer claimed = requiresNew.execute(status -> repository.compareAndSetStatus(
                id, ConfirmationStatus.PENDING, ConfirmationStatus.CONFIRMED, now));
        if (claimed == null || claimed == 0) {
            throw new BusinessException(ErrorCode.CONFIRMATION_NOT_ACTIVE, "该确认已被处理");
        }
        return entity;
    }

    /**
     * 第三段：把真实结果回写（独立事务）。
     *
     * <p>从 {@code CONFIRMED} 出发做 CAS：若该行已被别处改动（理论上不会发生），
     * 宁可不动也不覆盖 —— 记录状态比「一定要写成功」更重要。
     */
    private void recordOutcome(UUID id, ConfirmationStatus outcome) {
        requiresNew.executeWithoutResult(status -> {
            int updated = repository.compareAndSetStatus(id, ConfirmationStatus.CONFIRMED, outcome, Instant.now());
            if (updated == 0) {
                log.warn("待确认操作的结果回写未命中（状态已被改动） id={} outcome={}", id, outcome);
            }
        });
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
            case FAILED -> "失败";
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
