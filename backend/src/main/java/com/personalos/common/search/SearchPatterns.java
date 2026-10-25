package com.personalos.common.search;

/**
 * LIKE 模式构造（阶段 17）。
 *
 * <p>为什么需要它：用户输入的关键词里可能含 {@code %} 或 {@code _}，
 * 它们在 LIKE 里是通配符。不转义的话，搜 {@code 100%} 会命中「100 之后任意字符」，
 * 搜 {@code a_b} 会命中 {@code axb} —— 结果是「搜得越准，错得越巧」。
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
     * 构造「包含关键词」的 LIKE 模式，并转义其中的通配符。
     *
     * @param keyword 已 trim 的关键词
     * @return {@code %keyword%}，其中 {@code \ % _} 已转义
     */
    public static String likePattern(String keyword) {
        String escaped = keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
