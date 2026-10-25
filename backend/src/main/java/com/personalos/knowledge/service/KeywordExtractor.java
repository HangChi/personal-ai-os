package com.personalos.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从查询里提取「适合做关键词精确匹配」的 token（2026-10-26，混合检索用）。
 *
 * <h2>为什么需要它</h2>
 * 关键词通道做的是 {@code content ILIKE '%token%'} 子串匹配。若直接把整句查询当
 * token，中文长句几乎必然不命中（{@code '索引在什么情况下会失效' as a substring}
 * 在正文里不存在）；而英文标识符恰恰是向量检索最弱、关键词最擅长的部分。
 *
 * <p>评测里 6 条未命中全部落在这类 token 上：{@code watch} / {@code unknown} /
 * {@code any} / {@code 2PC} / {@code 3PC} / {@code ip_hash}。
 *
 * <h2>提取规则</h2>
 * <ul>
 *   <li>只取**英文与数字** token（正则 {@code [A-Za-z][A-Za-z0-9_+#.-]*}，长度 ≥ 2）。
 *       纯中文不做拆词 —— 那需要分词词典，引入依赖的收益远小于成本。</li>
 *   <li>过滤英文停用词（{@code what / how / the / and ...}）：它们在任何文档里都可能出现，
 *       带进关键词通道只会引入噪声。</li>
 *   <li>最多取 3 个：token 越多，AND 语义越严，越容易一条都不命中。</li>
 * </ul>
 *
 * <p>纯中文查询会得到空列表，此时关键词通道不产出候选、检索退化为纯向量 ——
 * 这是刻意的边界，不是缺陷：中文语义匹配交给向量，关键词只负责精确 token。
 */
public final class KeywordExtractor {

    /**
     * 英文/数字标识符：允许下划线、加号、井号、点号、连字符。
     *
     * <p><b>首字符允许数字</b>：{@code 2PC}、{@code 3PC}、{@code 1Panel} 都以数字开头，
     * 而它们正是评测里失败的那批查询。写成 {@code [A-Za-z]} 打头会让 {@code 2PC}
     * 只匹配到 {@code PC}，词元被截断 —— 关键词通道就救不回本来要救的查询。
     *
     * <p>纯数字 token 在别处被过滤（见 {@link #MUST_CONTAIN_LETTER}）：
     * {@code 100}、{@code 2026} 在技术文档里随处可见，做关键词匹配只会引入噪声。
     */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_+#.-]*");

    /** token 必须含至少一个字母：滤掉纯数字 */
    private static final Pattern MUST_CONTAIN_LETTER = Pattern.compile("[A-Za-z]");

    /** 最少字符数：单字母（{@code a}、{@code T}）在任何文档里都出现，没有区分度 */
    private static final int MIN_LENGTH = 2;

    /** 最多保留的 token 数 */
    private static final int MAX_TOKENS = 3;

    /** 英文停用词：这些词做关键词匹配只会引入噪声 */
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "an", "and", "or", "not", "is", "are", "was", "were", "be", "been",
            "to", "of", "in", "on", "at", "for", "with", "by", "from", "as", "it", "its",
            "this", "that", "these", "those", "what", "which", "who", "how", "why", "when",
            "where", "do", "does", "did", "can", "could", "should", "would", "will", "may",
            "might", "must", "have", "has", "had", "if", "then", "than", "so", "but",
            "vs", "vs.", "eg", "ie", "etc",
            // 「怎么使用 X」里的 use 本身没有区分度，任何教程都满篇是它
            "use", "uses", "using", "used", "way", "ways");

    private KeywordExtractor() {
    }

    /**
     * 提取可用于精确匹配的 token。
     *
     * @param query 原始查询
     * @return 去重后的 token（已转小写、保持出现顺序）；无可用 token 时返回空列表
     */
    public static List<String> extract(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        // LinkedHashSet 保持出现顺序并去重：先出现的通常更像"主语"
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(query);
        while (matcher.find() && tokens.size() < MAX_TOKENS) {
            String raw = matcher.group();
            // 去掉尾部的点号：`Object.defineProperty` 这类会带尾点，`vs.` 也要处理
            String token = raw.replaceAll("[.]+$", "");
            if (token.length() < MIN_LENGTH || !MUST_CONTAIN_LETTER.matcher(token).find()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(lower)) {
                continue;
            }
            tokens.add(lower);
        }
        return new ArrayList<>(tokens);
    }
}
