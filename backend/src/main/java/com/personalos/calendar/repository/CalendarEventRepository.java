package com.personalos.calendar.repository;

import com.personalos.calendar.entity.CalendarEventEntity;
import com.personalos.calendar.entity.RecurrenceType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 日程仓储。
 *
 * <p>阶段 17 起追加 {@link JpaSpecificationExecutor}：全局搜索要按标题/描述/地点筛选。
 */
public interface CalendarEventRepository extends JpaRepository<CalendarEventEntity, UUID>,
        JpaSpecificationExecutor<CalendarEventEntity> {

    /**
     * 取出可能与 {@code [from, to]} 有交集的日程（**原始定义**，不是展开后的实例）。
     *
     * <p>三个条件的用意：
     * <ul>
     *   <li>{@code startsAt <= to} —— 开始时间晚于区间终点的日程，任何一次发生都不在区间内；</li>
     *   <li>{@code recurrenceType <> NONE or startsAt >= from} —— 一次性日程若早已结束就不必取出。
     *       重复日程则不能这样剪枝：它的首次发生可能很久以前，但仍在区间内有发生；
     *       因此这里用参数传入 {@code NONE} 而不是写死枚举字面量（JPQL 里写类名限定字面量很脆）；</li>
     *   <li>{@code recurrenceUntil is null or recurrenceUntil >= from} —— 重复已截止的日程可以整条排除。</li>
     * </ul>
     *
     * <p>本方法只做**粗筛**：真正的「是否落在区间内」由 {@code RecurrenceCalculator} 判定。
     * 这样剪枝逻辑与重复语义互不纠缠，改重复规则不需要动这条查询。
     */
    @Query("""
            select e from CalendarEventEntity e
            where e.startsAt <= :to
              and (e.recurrenceType <> :singleType or e.startsAt >= :from)
              and (e.recurrenceUntil is null or e.recurrenceUntil >= :from)
            order by e.startsAt asc
            """)
    List<CalendarEventEntity> findCandidates(@Param("from") Instant from,
                                             @Param("to") Instant to,
                                             @Param("singleType") RecurrenceType singleType);
}
