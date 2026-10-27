package com.personalos.chat.service;

import com.personalos.chat.entity.TokenUsageEntity;
import com.personalos.chat.repository.TokenUsageRepository;
import com.personalos.chat.support.StreamUsage;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 把一轮对话的用量写进 {@code token_usage}。
 *
 * <h2>为什么异步</h2>
 * 写入发生在 {@code done} 事件的回调链上。用户在这一刻已经拿到完整回答，
 * 一次数据库往返（几十毫秒，表的索引还会随使用增长）纯粹是让「回答看起来更晚结束」。
 * 因此投递到 {@code contextTaskExecutor} —— 它已是有界队列 + 丢弃策略，
 * 模型变慢导致任务堆积时宁可丢几条统计，也不排队。
 *
 * <h2>为什么吞掉所有异常</h2>
 * 用量是**事后统计**，不是业务数据。它失败的正确后果是「趋势图少一个点」，
 * 而不是「回答变成错误帧」或「会话删不掉」。因此这里连 {@code Error} 之外的
 * 一切异常都记日志后忽略 —— 与 {@code ChatTurnFinisher} 的处理保持一致。
 *
 * <p>代价是统计可能静默缺失。为了让它可被察觉：写入失败记 WARN 并带上会话标识；
 * 聚合接口在窗口内无任何记录时返回 {@code hasData=false}，
 * 前端据此显示「还没有用量数据」而不是画一张空的零值图（后者看起来像坏了）。
 */
@Service
public class TokenUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private final TokenUsageRepository repository;
    /** 轮末任务池：与摘要/记忆共用，见 ContextTaskConfig 的注释 */
    private final ExecutorService contextTaskExecutor;
    /** 注入 Clock 而非直接 Instant.now()：统计的日期归属必须能在测试里被固定 */
    private final Clock clock;

    public TokenUsageRecorder(TokenUsageRepository repository,
                              @Qualifier("contextTaskExecutor") ExecutorService contextTaskExecutor,
                              Clock clock) {
        this.repository = repository;
        this.contextTaskExecutor = contextTaskExecutor;
        this.clock = clock;
    }

    /**
     * 记录一轮用量（非阻塞）。
     *
     * @param conversationId 会话标识
     * @param usage          本轮用量；{@code null} 或无有效数字时静默跳过
     */
    public void record(String conversationId, StreamUsage usage) {
        if (usage == null || !usage.isMeaningful()) {
            return;
        }
        Instant now = clock.instant();
        try {
            contextTaskExecutor.execute(() -> persist(conversationId, usage, now));
        } catch (RejectedExecutionException ex) {
            // 队列满（DiscardPolicy 其实不会抛，但换策略或关闭时会），
            // 此时丢掉这条统计即可 —— 不让它影响已经结束的对话
            log.debug("用量记录被丢弃（队列满或已关闭） conversationId={}", conversationId);
        }
    }

    /**
     * 同步写入。
     *
     * <p>生产代码只经 {@link #record} 调用它（异步）；设为 {@code public} 是为了让
     * 集成测试能**指定时间戳**直接写入 —— 验证「按本地时区分组」必须能造出
     * 「东八区 00:30」这种跨 UTC 日界的数据，而经过异步入口就拿不到可控的
     * {@code createdAt}，只能靠 {@code Thread.sleep} 等到某个时刻，那不叫测试。
     *
     * <p>抽成单独方法而不是内联进 lambda 的另一个原因也在于此：
     * 测试要能直接验证「写进去的是什么」而不必等待线程池调度。
     */
    public void persist(String conversationId, StreamUsage usage, Instant at) {
        try {
            repository.save(new TokenUsageEntity(conversationId, usage.model(),
                    usage.promptTokens(), usage.completionTokens(), usage.source(), at));
        } catch (RuntimeException ex) {
            log.warn("用量写入失败（不影响本轮回答） conversationId={} source={}",
                    conversationId, usage.source(), ex);
        }
    }
}
