package com.personalos.common.ai;

/**
 * 上下文预算（阶段 22 重构）。
 *
 * <h2>为什么需要这个可变的「预算」</h2>
 * 原来的分母是 {@code app.ai.memory.max-context-chars}（一个写死的字符阈值），
 * 用户看到的是「252 / 12.0k」——既不是模型能力，也无法选择。
 * 现在拆成两个概念：
 * <ul>
 *   <li><b>模型窗口</b>（{@code contextWindowTokens}）：模型支持的最大上下文，配置给定；</li>
 *   <li><b>用户预算</b>（本类持有）：用户在模型窗口内选一个上限，例如 1M 的模型选 300K。</li>
 * </ul>
 *
 * <h2>为什么预算放服务端而不是随请求传</h2>
 * 窗口裁剪发生在 {@code JpaChatMemory.get()} 里，而它由 Spring AI 的
 * {@code MessageChatMemoryAdvisor} 调用，请求参数到不了那里。
 * 放服务端才能让「仪表盘显示的分母」与「实际裁剪阈值」是同一个数 ——
 *否则就是给用户看一个假数字。
 *
 * <p>代价是重启后回到默认值。本地单用户场景可接受；要多端一致再加持久化。
 */
@org.springframework.stereotype.Component
public class ContextBudgetService {

    /**
     * 字符 / token 折算比（中文口径）。
     *
     * <p><b>这个数是实测的，不是估的</b>（2026-10-27，qwen3.8-flash，
     * 用真实 {@code prompt_tokens} 差值法反推）：中文 1 token ≈ 1.68 字，
     * 这里取 1.6 略偏保守。原注释「英文实际约 4」也是错的 —— 实测 5.7，
     * 详见 {@link TokenEstimation}。
     *
     * <p>预算阈值故意继续用单一中文口径：预算是一个<b>用户可见的分母</b>
     * （「300K 里用了 18K」），分母必须稳定且可解释。若按每条消息的语言
     * 分别折算，同一个预算在不同会话里对应的字符数会产生肉眼可见的跳变，
     * 用户会以为预算被改了。估算真实用量时才按语言区分（见 {@link TokenEstimation}）。
     */
    public static final double CHARS_PER_TOKEN = TokenEstimation.CJK_CHARS_PER_TOKEN;

    private final AiProperties aiProperties;

    /** 用户选择的预算（token）。volatile：可能被设置接口并发修改 */
    private volatile int budgetTokens;

    /** 默认预算：32K（前端档位里的最小档）。原来是拿 max-context-chars 折算成 7.5K，     结果不在任何档位里，仪表盘显示「158 / 7.3K」却没有对应选项可点 */
    private static final int DEFAULT_BUDGET_TOKENS = 32_768;

    public ContextBudgetService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        int window = contextWindowTokens();
        this.budgetTokens = Math.min(DEFAULT_BUDGET_TOKENS, window);
    }

    /** 模型支持的上下文窗口（token） */
    public int contextWindowTokens() {
        return aiProperties.memory().contextWindowTokens();
    }

    /** 用户当前选择的预算（token） */
    public int budgetTokens() {
        return budgetTokens;
    }

    /**
     * 设置预算（token）。
     *
     * <p>收敛到 {@code [1, 模型窗口]}：超过模型窗口的预算没有意义
     * （模型根本吃不下），小于 1 会让窗口永远为空。
     */
    public void setBudgetTokens(int tokens) {
        int window = contextWindowTokens();
        this.budgetTokens = Math.min(Math.max(tokens, 1), window);
    }

    /** 折算成字符预算 —— 窗口裁剪用的就是这个数 */
    public int effectiveCharBudget() {
        return (int) Math.round(budgetTokens * CHARS_PER_TOKEN);
    }

    /**
     * 把字符数折算成 token（仪表盘用）。
     *
     * <p>用中文口径固定折算，与 {@link #effectiveCharBudget()} 互为逆运算 ——
     * 仪表盘的分子与分母必须同口径，否则会出现「占了 90% 却还没被裁剪」这种
     * 用户能直接看出来的矛盾。按语言区分口径的版本只用于估算实际 token 消耗。
     */
    public static int toTokens(int chars) {
        return TokenEstimation.toTokens(chars, CHARS_PER_TOKEN);
    }

    /**
     * 按文本真实构成折算 token（用于估算本轮实际用量）。
     *
     * <p>与 {@link #toTokens(int)} 的区别：那个是「预算口径」（稳定、可解释），
     * 这个是「实际用量口径」（中英文差 3.5 倍，必须区分）。
     */
    public static int toTokensByContent(String text) {
        return TokenEstimation.toTokens(text, text == null ? 0 : text.length());
    }
}
