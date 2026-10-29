package com.personalos.chat.service;

import com.personalos.chat.dto.UsageStatsDto;
import com.personalos.chat.entity.TokenUsageSource;
import com.personalos.chat.repository.TokenUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量聚合（清单第 7 项）。
 *
 * <h2>聚合放在 SQL 里而不是内存</h2>
 * 「最近 30 天按天求和」在数据量大之后是几十万行的扫描。把 {@code GROUP BY} 交给
 * PostgreSQL，用 {@code idx_token_usage_created_at} 先收窄窗口，只有几十行回内存。
 * 反过来「先 findAll 再 stream 分桶」在开发机上永远看不出问题。
 *
 * <h2>时区只有一处真相</h2>
 * 分组的「天」与展示的「天」必须是同一个时区。这里把 {@code zoneId} 同时用于
 * ①SQL 的 {@code AT TIME ZONE}；②计算窗口的起止。若只传其中之一，
 * 「今天」的边界就会错开几小时，跨日的对话会被算到前一天。
 */
@Service
public class UsageStatsService {

    /** 窗口上限：一年。再长的趋势图在个人场景下没有可读性，也会拖慢聚合 */
    public static final int MAX_DAYS = 365;
    public static final int DEFAULT_DAYS = 30;

    private final TokenUsageRepository repository;
    private final Clock clock;
    /** 与日程模块共用同一个 Bean（systemDefaultZone） */
    private final ZoneId zoneId;

    public UsageStatsService(TokenUsageRepository repository, Clock clock, ZoneId zoneId) {
        this.repository = repository;
        this.clock = clock;
        this.zoneId = zoneId;
    }

    /**
     * 某会话最近一轮的**真实** prompt token 数；没有真实记录时返回空。
     *
     * <p>给对话页的上下文仪表盘用：它的分子必须与 {@code done.promptTokens}
     * 同源，否则会出现「仪表盘说 103、done 说 4116」这种同一屏内自相矛盾的读数。
     *
     * <p>返回 {@code OptionalInt} 而不是 0 作为哨兵：0 是合法的真实用量
     * （空对话也可能被上游报到 0），用 0 表示「没有」会让调用方无法区分。
     */
    @Transactional(readOnly = true)
    public OptionalInt latestActualPromptTokens(String conversationId) {
        return repository
                .findFirstByConversationIdAndSourceOrderByCreatedAtDescIdDesc(
                        conversationId, TokenUsageSource.ACTUAL)
                .map(entity -> OptionalInt.of(entity.getPromptTokens() == null
                        ? 0 : entity.getPromptTokens()))
                .orElseGet(OptionalInt::empty);
    }

    /**
     * 按日聚合最近 {@code days} 天（含今天）。
     *
     * @param days 天数；null 或越界会被收敛到 {@code [1, MAX_DAYS]}
     */
    @Transactional(readOnly = true)
    public UsageStatsDto stats(Integer days) {
        int effectiveDays = normalizeDays(days);
        LocalDate today = LocalDate.now(clock.withZone(zoneId));
        // 窗口起点是「今天往前 days-1 天」的 00:00 本地时间，转成瞬时时刻交给 SQL
        LocalDate fromDate = today.minusDays(effectiveDays - 1L);
        Instant from = fromDate.atStartOfDay(zoneId).toInstant();
        // 终点取「明天 00:00」（不含），这样今天全天的记录都在窗口内；
        // 用 now() 会漏掉「此刻之后、今天结束前」那一段的既有数据（补录/时钟回拨场景）
        Instant to = today.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<Object[]> rows = repository.aggregateByDay(from, to, zoneId.getId());

        // 按日合并：同一太阳日下 ACTUAL 与 ESTIMATED 是两行，需要合成一行展示
        Map<String, DayAccumulator> byDate = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String date = String.valueOf(row[0]);
            String source = String.valueOf(row[1]);
            long prompt = toLong(row[2]);
            long completion = toLong(row[3]);
            long turns = toLong(row[4]);
            byDate.computeIfAbsent(date, key -> new DayAccumulator()).add(prompt, completion, turns, source);
        }

        List<UsageStatsDto.DailyUsageDto> daily = new ArrayList<>(byDate.size());
        long totalPrompt = 0;
        long totalCompletion = 0;
        long totalTurns = 0;
        long actualTokens = 0;
        long estimatedTokens = 0;
        for (Map.Entry<String, DayAccumulator> entry : byDate.entrySet()) {
            DayAccumulator day = entry.getValue();
            daily.add(new UsageStatsDto.DailyUsageDto(
                    entry.getKey(), day.promptTokens, day.completionTokens,
                    day.promptTokens + day.completionTokens, day.turns, day.isFullyEstimated()));
            totalPrompt += day.promptTokens;
            totalCompletion += day.completionTokens;
            totalTurns += day.turns;
            actualTokens += day.actualTokens;
            estimatedTokens += day.estimatedTokens;
        }

        return new UsageStatsDto(effectiveDays, totalPrompt, totalCompletion, totalPrompt + totalCompletion,
                totalTurns, actualTokens, estimatedTokens, !daily.isEmpty(), List.copyOf(daily));
    }

    /** 收敛天数；null 用默认值 */
    private int normalizeDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    /** JDBC 聚合列可能是 Long / BigInteger / BigDecimal，统一走 Number */
    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 单日累加器。
     *
     * <p>用可变对象而不是 record：聚合是逐行累加，每行新建一个不可变对象
     * 在这条路径上没有收益（不会有任何一方持有旧快照）。
     */
    private static final class DayAccumulator {
        private long promptTokens;
        private long completionTokens;
        private long turns;
        private long actualTokens;
        private long estimatedTokens;
        private long actualTurns;

        void add(long prompt, long completion, long turnCount, String source) {
            long tokens = prompt + completion;
            this.promptTokens += prompt;
            this.completionTokens += completion;
            this.turns += turnCount;
            if (TokenUsageSource.ACTUAL.name().equals(source)) {
                this.actualTokens += tokens;
                this.actualTurns += turnCount;
            } else {
                this.estimatedTokens += tokens;
            }
        }

        /**
         * 这一天是否**全部**为估算值。
         *
         * <p>口径是「没有任何一轮是真实的」，而不是「存在估算轮次」：
         * 界面上这一列的作用是提示「这天的数字不可对账」，
         * 只要有一天混了真实数据，总和就不是纯估算，标成估算反而误伤。
         */
        boolean isFullyEstimated() {
            return actualTurns == 0 && estimatedTokens > 0;
        }
    }
}
