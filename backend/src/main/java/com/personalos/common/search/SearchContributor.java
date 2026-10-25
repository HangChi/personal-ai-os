package com.personalos.common.search;

import java.util.List;

/**
 * 模块级搜索 SPI（阶段 17）。
 *
 * <p><b>为什么用 SPI 而不是让聚合服务 import 五个模块的 Service</b>：那样它会成为
 * 五个模块的共同依赖方，将来加第六个模块就要改它 —— 而「改一个原本正确的类」
 * 正是回归的来源。这与既有的 {@code ToolContributor} / {@code ExportContributor}
 * 是同一套思路：各模块自己说「我怎么搜」，聚合服务只负责收集。
 *
 * <p>实现必须是 {@code @Component}，由 Spring 收集全部实现注入
 * {@code List<SearchContributor>}。
 */
public interface SearchContributor {

    /** 模块中文名，用作结果页的分组标题 */
    String moduleName();

    /** 该模块的对象类型，与 {@link SearchHit#kind()} 一致 */
    String kind();

    /**
     * 搜索本模块。
     *
     * <p>实现 MUST NOT 抛出异常影响其他模块 —— 聚合服务虽已做 try-catch，
     * 但「自己该处理的自己处理」能让日志更准确（知道是哪个模块的哪类问题）。
     *
     * @param keyword 已 trim 且非空的关键词
     * @param limit   返回条数上限
     * @return 命中条目；无命中返回空列表（由聚合服务决定是否生成分组）
     */
    List<SearchHit> search(String keyword, int limit);
}
