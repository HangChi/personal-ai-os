package com.personalos.common.ai.config;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 配置下的模型清单（V23）。
 *
 * <h2>为什么只有「按配置整组取」与「按配置整组删」两种形态</h2>
 * 清单的写路径是**整体替换**：设置页提交的是整份表单，用户在模型列表里
 * 加一个、删一个之后点保存，语义是「清单现在长这样」。没有「单独改某个模型名」
 * 这种操作 —— 那等价于删一条加一条，而拆成两个方法只会让两次操作之间
 * 存在一个「清单少一条」的中间态。
 *
 * <p>因此不提供 {@code findByProfileIdAndKindAndModelName} 之类的方法：
 * 判重交给数据库的唯一约束 {@code uq_ai_model_profile_model_name}，
 * 应用层预先查一遍反而是「查了再写」的竞态。
 */
public interface AiModelProfileModelRepository
        extends JpaRepository<AiModelProfileModelEntity, Long> {

    /**
     * 某套配置的全部模型，按用途与展示顺序排。
     *
     * <p>排序写死在这里的理由与配置表相同：清单顺序是用户手工维护的展示顺序，
     * 若因数据库返回顺序不定而变化，用户会以为自己的排序没保存成功。
     *
     * @param profileId 配置 id
     */
    List<AiModelProfileModelEntity> findByProfileIdOrderByKindAscSortOrderAscIdAsc(UUID profileId);

    /**
     * 批量取多套配置的模型，供列表接口一次查完。
     *
     * <p>设置页的卡片列表需要每张卡片都显示模型数/模型名摘要。逐张卡片查一次
     * 是典型的 N+1 —— 本地库上看不出来，接上远程库就是「打开设置页要等一秒」。
     *
     * @param profileIds 配置 id 集合
     */
    List<AiModelProfileModelEntity> findByProfileIdInOrderByKindAscSortOrderAscIdAsc(
            Collection<UUID> profileIds);

    /** 删掉某套配置的全部模型（整体替换的第一步） */
    void deleteByProfileId(UUID profileId);

    /** 数一下某套配置有几个模型（测试与校验用） */
    long countByProfileId(UUID profileId);
}
