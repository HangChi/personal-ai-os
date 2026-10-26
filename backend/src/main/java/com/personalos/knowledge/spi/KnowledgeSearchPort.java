package com.personalos.knowledge.spi;

import com.personalos.knowledge.dto.KnowledgeHitDto;
import java.util.List;

/**
 * 知识库检索窄接口（阶段 19）。
 *
 * <p>存在的理由与阶段 12 的 {@code ExportContributor}、阶段 17 的
 * {@code SearchContributor} 相同：**跨模块能力走接口，不直拉对方的 Service**。
 * chat 模块只需要「给一个查询词、返回命中片段」这一件事，
 * 不应该知道检索是混合的、RRF 的、阈值多少。
 *
 * <p>实现方由 knowledge 模块提供（{@code KnowledgeRetrievalService}）。
 * topK 的收敛（null / 越界）由实现方负责——口径只有一份，在 knowledge 模块内。
 */
public interface KnowledgeSearchPort {

    /**
     * 检索知识库。
     *
     * @param query 检索词（用户消息原文或关键词）
     * @param topK  返回条数；为空或越界由实现方收敛到合法区间
     * @return 命中片段，按相关度排序；无命中时为空列表
     */
    List<KnowledgeHitDto> search(String query, Integer topK);
}
