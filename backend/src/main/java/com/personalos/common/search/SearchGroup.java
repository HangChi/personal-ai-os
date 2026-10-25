package com.personalos.common.search;

import java.util.List;

/**
 * 一个模块的搜索结果。
 *
 * <p>无命中的模块**不会**生成分组（而不是生成一个空分组）——
 * 结果页要回答「哪里有」，列一堆 0 条的模块只会增加扫读成本。
 *
 * @param module 模块中文名，用作分组标题
 * @param kind   该模块的对象类型，与 {@link SearchHit#kind()} 一致
 * @param hits   命中条目，顺序由贡献者决定
 */
public record SearchGroup(String module, String kind, List<SearchHit> hits) {
}
