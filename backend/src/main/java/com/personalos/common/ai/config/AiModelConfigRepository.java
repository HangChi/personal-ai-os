package com.personalos.common.ai.config;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 大模型配置仓储。
 *
 * <p>只有「取唯一那行」一个查法：表被 {@code CHECK (id = 1)} 约束成单行，
 * 因此不提供列表、分页、按条件筛选 —— 那些方法即使写了也永远返回 0 或 1 行。
 */
public interface AiModelConfigRepository extends JpaRepository<AiModelConfigEntity, Short> {

    /** 取当前生效的配置；从未保存过时为空（调用方回落到环境变量） */
    default Optional<AiModelConfigEntity> findSingleton() {
        return findById(AiModelConfigEntity.SINGLETON_ID);
    }
}
