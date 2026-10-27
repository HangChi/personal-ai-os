package com.personalos.common.ai;

/**
 * 字符数与 token 数的折算（实测标定，2026-10-27）。
 *
 * <h2>为什么不能用一个系数</h2>
 * 原先全站用 {@code CHARS_PER_TOKEN = 1.6} 一刀切。实测（qwen3.8-flash，
 * 真实 {@code prompt_tokens} 差值法，每点重复取最小值）：
 * <pre>
 *   中文：0.5955 token/字符  →  1 token ≈ 1.68 字
 *   英文：0.1755 token/字符  →  1 token ≈ 5.70 字符
 * </pre>
 * 单一系数必然错一头：按 1.6 算中文误差可接受（偏低 5%），但英文会把 token
 * <b>高估 3.5 倍</b> —— 一份 5 万字符的英文文档，实际约 8.8K token，
 * 却会显示成 31K，用户看到的是「快撑爆了」的假警报。
 *
 * <h2>为什么按「非 ASCII 占比」判语言而不是引入语言检测库</h2>
 * 这个折算只用于<b>估算</b>（真实用量由 {@code StreamUsage} 的 ACTUAL 路径覆盖），
 * 精度要求是量级正确，不是精确。引入 ICU/语言检测会为一个估算函数增加
 * 数 MB 依赖与不可预测的启动开销。而非 ASCII 占比恰好抓住了真正的分界：
 * CJK 字符几乎都落在非 ASCII，而英文技术文本里的非 ASCII 极少。
 *
 * <h2>标定方法与置信度</h2>
 * 用真实 API 调用反推，而非查文档：网上对「1 汉字几个 token」的说法从
 * 0.5 到 2 都有，跨度 4 倍，不可用。方法是发已知长度的文本、读 {@code prompt_tokens}，
 * 用两个长度的差值消掉系统提示与工具 schema 的固定开销（约 3183 token）。
 * 中英文两次回归的截距分别是 3186 与 3181 —— 高度一致，说明测量方法自洽。
 */
public final class TokenEstimation {

    /**
     * 中文为主的文本：1 token 约合多少字符。
     *
     * <p>实测 1.679。取 1.6 略偏保守（高估 token 约 5%）：
     * 让用户以为快满了，好过显示假的宽裕。
     */
    public static final double CJK_CHARS_PER_TOKEN = 1.6;

    /**
     * 英文为主的文本：1 token 约合多少字符。
     *
     * <p>实测 5.698。同样向下取整到 5.5 保持「略微高估」的一致方向。
     */
    public static final double LATIN_CHARS_PER_TOKEN = 5.5;

    /**
     * 非 ASCII 字符占比超过这个阈值就按中文口径折算。
     *
     * <p>0.25 的选取：纯英文技术文档的非 ASCII 占比通常 &lt;2%（偶尔的破折号、
     * 引号、作者名）；而中英混排的技术笔记通常在 40% 以上。0.25 落在两簇之间
     * 的空档里，不追求精确识别「这是中文还是英文」，只区分两种折算口径。
     */
    private static final double CJK_RATIO_THRESHOLD = 0.25;

    private TokenEstimation() {
    }

    /**
     * 按文本实际构成选择折算比。
     *
     * @param text 待估算的文本
     * @return 该文本的「字符 / token」比
     */
    public static double charsPerToken(String text) {
        if (text == null || text.isEmpty()) {
            return CJK_CHARS_PER_TOKEN;
        }
        int nonAscii = 0;
        // 按 code point 遍历：中文扩展区与 emoji 在 char 层面是两个代理项，
        // 按 char 统计会把它们算成「两个非 ASCII 字符」—— 占比仍然超阈值，方向无误，
        // 但 codePointCount 更准确，成本也一样
        int length = text.codePointCount(0, text.length());
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (codePoint > 127) {
                nonAscii++;
            }
            index += Character.charCount(codePoint);
        }
        double ratio = (double) nonAscii / length;
        return ratio >= CJK_RATIO_THRESHOLD ? CJK_CHARS_PER_TOKEN : LATIN_CHARS_PER_TOKEN;
    }

    /**
     * 字符数 → token 数。
     *
     * @param text  用于判断折算口径的文本
     * @param chars 该文本的字符数（可传 {@code text.length()}；
     *              单独传是为了让调用方复用已算好的统计）
     * @return 估算的 token 数，不小于 0
     */
    public static int toTokens(String text, int chars) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.max(Math.round(chars / charsPerToken(text)), 0);
    }

    /** 单系数版本的折算，用于已经知道口径的场景（如「预算按中文口径」） */
    public static int toTokens(int chars, double charsPerToken) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.max(Math.round(chars / charsPerToken), 0);
    }
}
