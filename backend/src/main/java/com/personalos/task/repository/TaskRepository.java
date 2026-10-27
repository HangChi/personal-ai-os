package com.personalos.task.repository;

import com.personalos.task.entity.TaskEntity;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 任务仓储。
 *
 * <p>采用 {@code JpaSpecificationExecutor} 而非「JPQL 里写一堆 {@code :param is null or ...}」：
 * 后者在参数为 null 时对类型推断很敏感，容易出现「sometimes 报类型不匹配」的隐性坑；
 * Specification 是类型安全的，条件按需拼装。
 */
public interface TaskRepository extends JpaRepository<TaskEntity, UUID>, JpaSpecificationExecutor<TaskEntity> {

    /**
     * 全部标签（去重、字典序），供筛选下拉使用。
     *
     * <p>用原生 SQL 而不是 JPQL：{@code task_tag} 是 {@code @ElementCollection} 的映射表，
     * 没有独立实体，JPQL 无法把它当作 from 的主体。
     * 删除任务会级联删标签，因此这里不会返回孤儿标签。
     */
    @Query(value = "SELECT DISTINCT tag FROM task_tag ORDER BY tag", nativeQuery = true)
    List<String> findDistinctTags();

    /**
     * 全量统计（审查 #16）：未完成数与逾期未完成数。
     *
     * <p>口径必须与 {@code TaskService.toDto} 的 {@code overdue} 判定一致：
     * {@code status != DONE} 且 {@code due_at} 早于 {@code now}。这里用一条 SQL 聚合，
     * 避免把整表捞进内存再数 —— 首页只需要两个数字。
     *
     * <p>{@code FILTER (WHERE ...)} 是 PG 专有语法，与「用原生 SQL 表达方言」的既有约定一致
     * （见 {@code token_usage} 的 {@code AT TIME ZONE}）。
     *
     * @param now 判定「逾期」的当前时刻（与列表接口用同一个 now 口径）
     * @return {@code [pending(long), overdue(long)]}
     */
    @Query(value = """
            SELECT COUNT(*) FILTER (WHERE status <> 'DONE')                                   AS pending,
                   COUNT(*) FILTER (WHERE status <> 'DONE' AND due_at IS NOT NULL
                                       AND due_at < :now)                                     AS overdue
              FROM task
            """, nativeQuery = true)
    TaskStatsRow countStats(@Param("now") Instant now);

    /**
     * {@link #countStats} 的结果投影。
     *
     * <p>用 interface projection 而不是 {@code Object[]}：Hibernate 对「单行多列」
     * 的原生聚合查询，声明 {@code Object[]} 返回时会把结果包成外层单元素数组
     * （{@code [ [pending, overdue] ]}），取 {@code row[1]} 直接越界。
     * 投影按列别名绑定，与结果集的行列形态无关。
     */
    interface TaskStatsRow {
        long getPending();

        long getOverdue();
    }
}
