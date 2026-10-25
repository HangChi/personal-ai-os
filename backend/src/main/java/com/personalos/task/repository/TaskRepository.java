package com.personalos.task.repository;

import com.personalos.task.entity.TaskEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

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
}
