package com.personalos.common.confirmation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import com.personalos.common.exception.ResourceNotFoundException;
import com.personalos.common.json.JsonCodec;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 确认链路的**事务结构**测试。
 *
 * <p>针对「占位与执行曾共用同一事务」造成的两个相反方向的缺陷。断言只落在
 * **库里最终是什么状态** —— 缺陷的本质就是「库里的状态与用户看到的事实不一致」，
 * 断言日志或异常类型都会漏掉它。
 *
 * <h2>为什么手工装配 ConfirmationService</h2>
 * 需要一个可编排的替身处理器来稳定制造各种失败。若把它注册成 bean（哪怕 {@code @Primary}），
 * {@link ConfirmationRegistry} 会在启动期检测到「同一个动作有两个处理器」而**直接启动失败**——
 * 那正是它该做的事，不该为了测试放松。因此这里手工 new 一个
 * {@code ConfirmationService}，只替换处理器，事务管理器和仓储仍用容器里的真实实现。
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none"})
@Testcontainers
class ConfirmationTransactionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("pgvector/pgvector:pg17");

    @Autowired
    ConfirmationRepository confirmationRepository;

    @Autowired
    JsonCodec jsonCodec;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    FlakyHandler handler;
    ConfirmationService service;

    @BeforeEach
    void setUp() {
        confirmationRepository.deleteAll();
        handler = new FlakyHandler();
        // 替身覆盖全部动作，ConfirmationRegistry 的启动期校验才会通过
        ConfirmationRegistry registry = new ConfirmationRegistry(List.of(handler));
        service = new ConfirmationService(confirmationRepository, registry, jsonCodec, transactionManager);
    }

    /** 造一条待确认记录（payload 内容由替身忽略） */
    private ConfirmationDto pending() {
        return service.create(FlakyHandler.ACTION, "测试动作", "{}", 1, "conv-tx");
    }

    private ConfirmationStatus statusOf(UUID id) {
        return confirmationRepository.findById(id).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("执行抛业务异常 → 记录不得停在 CONFIRMED（否则用户以为已生效，操作永久丢失）")
    void businessExceptionDoesNotLeaveConfirmedStatus() {
        ConfirmationDto confirmation = pending();
        handler.failWith(new BusinessException(ErrorCode.AI_NOT_CONFIGURED, "MCP 未启用"));

        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class);

        // 这正是原缺陷的核心断言：改前这里会是 CONFIRMED
        assertThat(statusOf(confirmation.id()))
                .as("执行失败必须如实记录，不能伪装成已执行")
                .isEqualTo(ConfirmationStatus.FAILED);
    }

    @Test
    @DisplayName("执行抛非业务运行时异常 → 同样记为 FAILED，且异常照常上抛")
    void runtimeExceptionIsRecordedAsFailed() {
        ConfirmationDto confirmation = pending();
        handler.failWith(new IllegalStateException("连接池已耗尽"));

        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statusOf(confirmation.id())).isEqualTo(ConfirmationStatus.FAILED);
    }

    @Test
    @DisplayName("执行抛「目标不存在」→ 记为 CANCELLED 而非 FAILED（用户不需要重试）")
    void resourceNotFoundIsCancelledNotFailed() {
        ConfirmationDto confirmation = pending();
        handler.failWith(new ResourceNotFoundException("目标任务已不存在"));

        ConfirmationExecution execution = service.confirm(confirmation.id(), confirmation.token());

        assertThat(execution.executed()).isFalse();
        assertThat(execution.affectedCount()).isZero();
        assertThat(execution.summary()).contains("目标已不存在");
        assertThat(statusOf(confirmation.id())).isEqualTo(ConfirmationStatus.CANCELLED);
    }

    @Test
    @DisplayName("成功路径：handler 被调用一次，状态最终为 CONFIRMED")
    void successRecordsConfirmed() {
        ConfirmationDto confirmation = pending();
        handler.succeedWith(3);

        ConfirmationExecution execution = service.confirm(confirmation.id(), confirmation.token());

        assertThat(execution.executed()).isTrue();
        assertThat(execution.affectedCount()).isEqualTo(3);
        assertThat(execution.summary()).contains("已执行");
        assertThat(handler.calls()).isEqualTo(1);
        assertThat(statusOf(confirmation.id())).isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("单次性：执行失败后令牌同样作废，不能再确认一次")
    void failedConfirmationIsStillSingleUse() {
        ConfirmationDto confirmation = pending();
        handler.failWith(new BusinessException(ErrorCode.AI_NOT_CONFIGURED, "第一次失败"));

        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class);

        // 令牌已被占位，第二次得到「已失败，无法再次执行」而不是重新执行一遍
        handler.succeedWith(1);
        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("失败");
        assertThat(handler.calls()).as("第二次不应真的执行").isEqualTo(1);
    }

    @Test
    @DisplayName("占位先于执行：handler 执行期读到的已是 CONFIRMED（证明占位独立提交）")
    void claimCommitsBeforeHandlerRuns() {
        ConfirmationDto confirmation = pending();
        handler.observe(confirmation.id(), confirmationRepository);

        ConfirmationExecution execution = service.confirm(confirmation.id(), confirmation.token());

        assertThat(execution.executed()).isTrue();
        assertThat(handler.observedStatus())
                .as("执行时占位应已提交；若读到 PENDING，说明占位与执行又回到同一事务")
                .isEqualTo(ConfirmationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("执行失败不回滚已提交的占位：状态是 FAILED 而不是 PENDING")
    void failureDoesNotRollbackClaim() {
        ConfirmationDto confirmation = pending();
        handler.failWith(new IllegalStateException("执行期崩溃"));

        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(IllegalStateException.class);

        ConfirmationEntity stored = confirmationRepository.findById(confirmation.id()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ConfirmationStatus.FAILED);
        assertThat(stored.getResolvedAt()).as("回写应带上解决时间").isNotNull();
    }

    @Test
    @DisplayName("过期 → 置 EXPIRED 后抛错，标记不被回滚（用户与库看到同一事实）")
    void expiredMarkerSurvivesTheException() {
        ConfirmationDto confirmation = pending();
        // 直接把过期时间压到过去。实体上没有 setter（也不该为了测试加一个），故走原生 SQL
        jdbcTemplate.update("update confirmation set expires_at = ? where id = ?",
                OffsetDateTime.now().minusMinutes(5), confirmation.id());

        assertThatThrownBy(() -> service.confirm(confirmation.id(), confirmation.token()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("过期");

        assertThat(statusOf(confirmation.id()))
                .as("过期标记必须在抛错前独立提交，不能被异常回滚掉")
                .isEqualTo(ConfirmationStatus.EXPIRED);
        assertThat(handler.calls()).as("过期不得执行").isZero();
    }

    // ------------------------------------------------------------------ 替身处理器

    /**
     * 可编排的替身处理器，占用一个真实存在的动作位（{@code DELETE_TASK}）。
     *
     * <p>必须覆盖**全部** {@link ConfirmationAction} 取值：{@link ConfirmationRegistry}
     * 在构造期校验「每个动作都有处理器」，只声明一个会直接抛错。
     */
    static class FlakyHandler implements ConfirmationActionHandler {

        static final ConfirmationAction ACTION = ConfirmationAction.DELETE_TASK;

        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;
        private int affected = 1;
        private ConfirmationStatus observed;
        private UUID observeTarget;
        private ConfirmationRepository observeRepository;

        @Override
        public Set<ConfirmationAction> supportedActions() {
            return Set.of(ConfirmationAction.values());
        }

        @Override
        public int execute(ConfirmationAction action, String payloadJson) {
            calls.incrementAndGet();
            if (observeTarget != null && observeRepository != null) {
                observed = observeRepository.findById(observeTarget)
                        .map(ConfirmationEntity::getStatus).orElse(null);
            }
            if (failure != null) {
                throw failure;
            }
            return affected;
        }

        void failWith(RuntimeException ex) {
            this.failure = ex;
        }

        void succeedWith(int affectedRows) {
            this.affected = affectedRows;
        }

        /** 让处理器在执行期间回读自身状态，用于验证「占位先提交」 */
        void observe(UUID id, ConfirmationRepository repository) {
            this.observeTarget = id;
            this.observeRepository = repository;
        }

        int calls() {
            return calls.get();
        }

        ConfirmationStatus observedStatus() {
            return observed;
        }
    }
}
