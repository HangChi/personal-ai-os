package com.personalos.memory.repository;

import com.personalos.memory.entity.MemoryEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
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

    /**
     * 全部记忆，按「常驻优先、再按最近修改」倒序。
     *
     * <p>V26：常驻条目必须排在前面 —— 注入是「先到先占预算」的，
     * 若常驻条目排在几十条之后，它会在预算耗尽时被挤掉，
     * 而「常驻」的全部意义就是不被挤掉。
     */
    List<MemoryEntity> findAllByOrderByPinnedDescUpdatedAtDesc();

    /**
     * 仍参与注入的记忆（未被取代），按最近修改倒序。
     *
     * <p>筛选放在 SQL 而不是内存里：注入的候选窗口固定 120 条，
     * 若先取 120 条再过滤掉已被取代的，实际候选会少于 120 条 ——
     * 记忆越多、被取代的越多，候选就越少，是一个会随使用时间恶化的静默退化。
     */
    List<MemoryEntity> findBySupersededByIsNullOrderByUpdatedAtDesc(Pageable pageable);

    /** 常驻条目数（用于「超过上限时按最近更新保留」的提示与截断） */
    long countByPinnedTrueAndSupersededByIsNull();

    /**
     * 前 N 条记忆，按最近修改倒序（提示词注入专用）。
     *
     * <p>与 {@link #findAllByOrderByUpdatedAtDesc()} 同一顺序，只是把「取多少条」
     * 交给调用方（{@code Pageable}）。注入有 30 条上限，全表加载再在内存里 break
     * 的写法在记忆攒到几千条时纯属浪费。
     */
    List<MemoryEntity> findByOrderByUpdatedAtDesc(Pageable pageable);

    /** 按内容查找（用于显式写入的幂等去重，见 research R-06） */
    Optional<MemoryEntity> findFirstByContent(String content);

    /** 按内容哈希查找（V17 唯一键，去重的硬约束依据） */
    Optional<MemoryEntity> findByContentHash(String contentHash);
}
