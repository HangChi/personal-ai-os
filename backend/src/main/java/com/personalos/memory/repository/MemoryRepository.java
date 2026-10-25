package com.personalos.memory.repository;

import com.personalos.memory.entity.MemoryEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * 长期记忆仓储。
 *
 * <p>阶段 17 起追加 {@link JpaSpecificationExecutor}：全局搜索要按内容筛选。
 */
public interface MemoryRepository extends JpaRepository<MemoryEntity, UUID>,
        JpaSpecificationExecutor<MemoryEntity> {

    /**
     * 全部记忆，按最近修改倒序。
     *
     * <p>同一个顺序同时用于列表展示与提示词注入 —— 两处不一致会让用户无法预期
     * 「预算用尽时到底哪几条生效」（FR-009 / contracts 注入契约）。
     */
    List<MemoryEntity> findAllByOrderByUpdatedAtDesc();

    /** 按内容查找（用于显式写入的幂等去重，见 research R-06） */
    Optional<MemoryEntity> findFirstByContent(String content);
}
