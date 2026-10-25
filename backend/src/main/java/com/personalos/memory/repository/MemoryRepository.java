package com.personalos.memory.repository;

import com.personalos.memory.entity.MemoryEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 长期记忆仓储。
 *
 * <p>没有自定义 SQL：查询只有「按更新时间倒序全取」和「按内容精确找一条」两种，
 * 由方法名派生即可。数据量预期几十至几百行，不为它加索引或分页（research R-08）。
 */
public interface MemoryRepository extends JpaRepository<MemoryEntity, UUID> {

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
