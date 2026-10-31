package com.personalos.common.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 跨模块搜索聚合服务。
 *
 * <p>只做三件事：按模块名排序（保证输出稳定）、收集分组、**隔离单个模块的故障**。
 * 搜索逻辑完全在各模块的 {@link SearchContributor} 里。
 */
@Service
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);

    private final List<SearchContributor> contributors;

    public GlobalSearchService(List<SearchContributor> contributors) {
        this.contributors = contributors;
    }

    /**
     * 搜索全部模块。
     *
     * <p><b>故障隔离</b>：单个贡献者抛异常时记 warn 并跳过该模块，请求仍返回 200。
     * 聚合查询最坏的失败模式是「一个模块挂 → 整个搜索白屏」，
     * 而用户此时最需要的是其他模块的结果。
     * 各贡献者自行开启只读事务；聚合层不共享事务，避免 SQL 错误污染后续查询。
     */
    public GlobalSearchResult search(String keyword, int limit) {
        List<SearchContributor> ordered = contributors.stream()
                .sorted(Comparator.comparing(SearchContributor::moduleName))
                .toList();

        List<SearchGroup> groups = new ArrayList<>();
        for (SearchContributor contributor : ordered) {
            try {
                List<SearchHit> hits = contributor.search(keyword, limit);
                if (!hits.isEmpty()) {
                    groups.add(new SearchGroup(contributor.moduleName(), contributor.kind(), hits));
                }
            } catch (RuntimeException ex) {
                log.warn("模块「{}」搜索失败，已跳过 keyword={}", contributor.moduleName(), keyword, ex);
            }
        }

        int totalHits = groups.stream().mapToInt(group -> group.hits().size()).sum();
        return new GlobalSearchResult(keyword, totalHits, groups);
    }
}
