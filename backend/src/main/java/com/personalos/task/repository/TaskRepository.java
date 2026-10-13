package com.personalos.task.repository;

import com.personalos.task.entity.TaskEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 任务仓储。
 *
 * <p>采用 {@code JpaSpecificationExecutor} 而非「JPQL 里写一堆 {@code :param is null or ...}」：
 * 后者在参数为 null 时对类型推断很敏感，容易出现「sometimes 报类型不匹配」的隐性坑；
 * Specification 是类型安全的，条件按需拼装。
 */
public interface TaskRepository extends JpaRepository<TaskEntity, UUID>, JpaSpecificationExecutor<TaskEntity> {
}
