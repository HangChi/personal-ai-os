package com.personalos.common.search;

import java.util.Locale;

/**
 * LIKE 模式构造（阶段 17）。
 *
 * <p>为什么需要它：用户输入的关键词里可能含 {@code %} 或 {@code _}，
 * 它们在 LIKE 里是通配符。不转义的话，搜 {@code 100%} 会命中「100 之后任意字符」，
 * 搜 {@code a_b} 会命中 {@code axb} —— 结果是「搜得越准，错得越巧」。
 *
 * <p><b>返回的模式一律是小写的</b>，因此只能与「小写化之后的列」比较。这条约束不是
 * 风格问题：所有调用方都用 {@code builder.lower(column)} 或 SQL 的 {@code ILIKE}，
 * 若关键词保留原大小写，搜 {@code Vue} / {@code API} 会得到 0 结果，而搜小写正常 ——
 * 表现为「搜索时灵时不灵」，很难归因到大小写。
 *
 * <p>JPA Criteria 与原生 SQL 都要显式声明转义符，否则转义后的反斜杠会被当成普通字符：
 * <pre>
 * builder.like(builder.lower(root.get("title")), likePattern(kw), ESCAPE);
 * ... WHERE content ILIKE ? ESCAPE '\'      -- 参数用 likePattern(kw)
 * </pre>
 */
public final class SearchPatterns {

    /** 与 {@link #likePattern(String)} 配套的转义符 */
    public static final char ESCAPE = '\\';

    private SearchPatterns() {
    }

    /**
     * 构造「包含关键词」的 LIKE 模式：小写化 + 转义通配符 + 两端补 {@code %}。
     *
     * @param keyword 关键词（内部会 strip；允许用户在首尾带空格）
     * @return {@code %keyword%}，其中 {@code \ % _} 已转义，且已转小写
     */
    public static String likePattern(String keyword) {
        String escaped = keyword.strip()
                .toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
