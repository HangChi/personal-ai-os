package com.personalos.chat.repository;

import com.personalos.chat.entity.TokenUsageEntity;
import com.personalos.chat.entity.TokenUsageSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用量仓储。
 *
 * <p>只有两类操作：写入一行（{@code save}）与按时间窗口聚合。
 * 不做「按会话查询」——用量是账号级统计，会话级明细在对话页已有仪表盘。
 */
public interface TokenUsageRepository extends JpaRepository<TokenUsageEntity, Long> {

    /**
     * 按**天**聚合窗口内的用量。
     *
     * <h2>为什么把时区当参数传进 SQL</h2>
     * {@code created_at} 是 {@code TIMESTAMPTZ}，直接 {@code date_trunc('day', created_at)}
     * 会按**数据库会话时区**（容器里通常是 UTC）截断，于是「北京时间 8 点前」的记录
     * 被算进前一天。用户在界面上看到的「今天」与这里的分组必须用同一个时区，
     * 否则趋势图上的最高峰可能落在错误的日子上。
     *
     * <p>{@code (created_at AT TIME ZONE :zone)} 先转成该时区的「墙上时间」再截断，
     * 分组与展示就一致了。
     *
     * <h2>为什么用原生 SQL</h2>
     * JPQL 不支持 {@code AT TIME ZONE} 与 {@code date_trunc}。这里没有可移植性诉求
     * （项目只跑 PostgreSQL），用原生 SQL 换来的是「分组口径写在查询里」而不是
     * 把整窗数据捞进内存再分桶。
     *
     * @param from 窗口起点（含）
     * @param to   窗口终点（不含）
     * @param zone 分组所用时区（如 {@code Asia/Shanghai}）
     * @return 每行 {@code [day(String yyyy-MM-dd), source(String), promptTokens(long),
     *         completionTokens(long), turns(long)]}
     */
    @Query(value = """
            SELECT to_char((created_at AT TIME ZONE :zone)::date, 'YYYY-MM-DD') AS day,
                   source,
                   COALESCE(SUM(prompt_tokens), 0)     AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COUNT(*)                            AS turns
              FROM token_usage
             WHERE created_at >= :from AND created_at < :to
             GROUP BY day, source
             ORDER BY day
            """, nativeQuery = true)
    List<Object[]> aggregateByDay(@Param("from") Instant from,
                                  @Param("to") Instant to,
                                  @Param("zone") String zone);

    /** 删除早于某个时刻的记录（保留期清理用） */
    int deleteByCreatedAtBefore(Instant threshold);

    /**
     * 某会话最近一条**真实**（非估算）用量。
     *
     * <p>与类注释里「不做按会话查询」的关系：那条约束针对的是**统计页** ——
     * 统计是账号级的，按会话切片没有意义。这里是另一个用途：对话页的上下文仪表盘
     * 需要一个「真实的已用量」，而字符估算在工具定义占大头时会低一个数量级
     * （实测 103 vs 4116）。宁可复用同一张表，也不为它单开一列/一张表。
     *
     * <p>只取 ACTUAL：估算值不值得用第二次 —— 仪表盘自己就能算，且口径未必相同。
     */
    Optional<TokenUsageEntity> findFirstByConversationIdAndSourceOrderByCreatedAtDescIdDesc(
            String conversationId, TokenUsageSource source);
}
