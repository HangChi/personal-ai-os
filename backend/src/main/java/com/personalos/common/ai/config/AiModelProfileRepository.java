package com.personalos.common.ai.config;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * 大模型配置仓储（V21 多行表）。
 *
 * <h2>为什么排序写死在查询里</h2>
 * 列表顺序是**契约**，不是调用方的自由：设置页每次刷新若顺序发生变化，
 * 用户会以为卡片被重新排列了（而实际只是数据库返回顺序不定）。
 * 因此按 {@code sort_order, created_at} 固定排序，后者兜底相同 sortOrder
 * 的情况，让顺序在任何情况下都稳定可复现。
 *
 * <h2>为什么没有「批量取消激活」的方法</h2>
 * 曾写过一个 {@code UPDATE ... SET active = false WHERE active = true} 的
 * {@code @Modifying} 查询，想用一条语句绕过部分唯一索引。它能工作，但要付
 * {@code clearAutomatically} 的代价：批量 UPDATE 绕过一级缓存，内存里那些
 * 实体仍是 {@code active = true}，切换后立刻读回来会看到旧值。
 *
 * <p>现在改用「逐行取消 + {@code saveAndFlush} + 再激活」，
 * 由 {@link ModelProfileService#activate} 控制顺序。显式 flush 已经把
 * FALSE 落到数据库，紧随其后的 TRUE 就不可能撞唯一索引 ——
 * 而且缓存始终与数据库一致。
 */
public interface AiModelProfileRepository extends JpaRepository<AiModelProfileEntity, UUID> {

    /** 全部配置，按展示顺序 */
    List<AiModelProfileEntity> findAllByOrderBySortOrderAscCreatedAtAsc();

    /**
     * 当前生效的那一套。
     *
     * <p>用 {@code findFirstBy} 而不是返回 List：部分唯一索引已保证至多一行，
     * 返回 List 会让每个调用点都写一遍「长度为 1」的假设。
     */
    Optional<AiModelProfileEntity> findFirstByActiveTrue();

    /**
     * 取最大排序位，用于追加到末尾；空表时为 null。
     *
     * <p>用 {@code MAX} 而不是 {@code COUNT}：删过卡片后 count 会缩小，
     * 新卡片的 sortOrder 就可能与既有卡片撞值 —— 结果虽然仍靠 created_at
     * 兜底保持稳定，但「新加的排在最后」这个直觉在删除后就失效了。
     */
    @Query("SELECT MAX(p.sortOrder) FROM AiModelProfileEntity p")
    Integer maxSortOrder();
}
