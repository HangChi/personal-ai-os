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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ContextBudgetService.class);

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

    /**
     * 按模型名查窗口的钩子（V25）。
     *
     * <p>用函数式依赖而不是直接注入 {@code ModelProfileService}：后者在构造期
     * 依赖 {@code ModelConfigService}，而 Config 服务又可能回头要本类 —— 直接注入
     * 会形成循环。用 {@code Supplier} 形式的查询器把依赖方向掰直：
     * 谁拥有模型清单谁注册进来，且可以晚于本类构造。
     *
     * <p>刻意**不做**成 Spring 事件或静态字段：一个 setter 注册即可，
     * 多一层间接只会让「谁在什么时候被注册」变难追。
     */
    private volatile java.util.function.Function<String, Integer> windowLookup = name -> null;

    /** 用户选择的预算（token）。volatile：可能被设置接口并发修改 */
    private volatile int budgetTokens;

    /**
     * 固定开销的来源（基础人设 + 工具定义）。默认 0，由 {@code ChatPromptAssembler} 注册。
     *
     * <p>用函数式依赖而不是直接注入的原因见 {@link #fixedOverheadTokens()}。
     */
    private volatile java.util.function.IntSupplier fixedOverhead = () -> 0;

    /**
     * 扣掉固定开销后至少要留给历史的那一段（token）。
     *
     * <p>取 1024 而不是 0：0 会让「预算 = 固定开销」退化成「一条历史都不带」，
     * 用户看到的是一个能对话但完全失忆的助手，比报错更难理解。
     */
    private static final int MIN_HISTORY_TOKENS = 1_024;

    /** 默认预算：32K（前端档位里的最小档）。原来是拿 max-context-chars 折算成 7.5K，     结果不在任何档位里，仪表盘显示「158 / 7.3K」却没有对应选项可点 */
    private static final int DEFAULT_BUDGET_TOKENS = 32_768;

    public ContextBudgetService(AiProperties aiProperties) {
        this.aiProperties = aiProperties;
        int window = contextWindowTokens();
        this.budgetTokens = Math.min(DEFAULT_BUDGET_TOKENS, window);
    }

    /**
     * 注册「按模型名查窗口」的查询器。
     *
     * @param lookup 输入模型名，返回该模型的窗口；未知返回 null。
     *               必须是幂等、无副作用的读操作 —— 它会在每次取窗口时被调用。
     */
    public void registerWindowLookup(java.util.function.Function<String, Integer> lookup) {
        this.windowLookup = lookup == null ? name -> null : lookup;
    }

    /** 模型支持的上下文窗口（token）—— 全局默认值，不看具体模型 */
    public int contextWindowTokens() {
        return aiProperties.memory().contextWindowTokens();
    }

    /**
     * 某个模型支持的上下文窗口（token）。
     *
     * <p>三级回落，顺序不能颠倒：
     * <ol>
     *   <li><b>配置里该模型的窗口</b>：用户在设置页填的，或探测/内置表带出来的；</li>
     *   <li><b>内置对照表</b>：配置里没填但模型名能认出来（{@code qwen-max} → 32K）；</li>
     *   <li><b>全局默认</b>：都不认识时用 {@code app.ai.memory.context-window-tokens}。</li>
     * </ol>
     *
     * <p>这里查内置表是**有必要**的重复：配置可能在 V25 之前就建好了
     * （那时没有窗口列，值为 NULL），而模型名本身是可识别的。
     * 不查表就会让存量配置一律回落到 128K，等于本次改造对老用户无效。
     *
     * @param modelName 模型名；null/空 表示「没有具体模型」，直接返回全局默认
     */
    public int contextWindowTokens(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return contextWindowTokens();
        }
        String name = modelName.strip();
        try {
            Integer fromConfig = windowLookup.apply(name);
            if (fromConfig != null && fromConfig >= 1024) {
                return fromConfig;
            }
        } catch (RuntimeException ex) {
            // 查询器读数据库失败不能让整个聊天挂掉：回落到默认值，记一笔即可
            log.warn("查询模型 {} 的上下文窗口失败，回落到内置表/全局默认：{}", name, ex.toString());
        }
        Integer known = ModelContextWindows.lookup(name);
        if (known != null) {
            return known;
        }
        return contextWindowTokens();
    }

    /** 用户当前选择的预算（token） */
    public int budgetTokens() {
        return budgetTokens;
    }

    /**
     * 一份请求里**不可压缩的固定开销**（token）：基础人设 + 工具定义。
     *
     * <p>由 {@code ChatPromptAssembler} 在构造期注册进来，而不是在这里注入 ——
     * 本类被 {@code JpaChatMemory} 依赖，而工具集合又经 {@code ChatMemory} 间接依赖本类，
     * 直接注入会形成构造器环。
     *
     * <p>注册前的默认值是 0：那段时间内的行为与改造前一致（预算只约束历史），
     * 属于可接受的降级，而不是错误。
     */
    public int fixedOverheadTokens() {
        try {
            return Math.max(fixedOverhead.getAsInt(), 0);
        } catch (RuntimeException ex) {
            log.warn("读取固定开销失败，按 0 处理：{}", ex.toString());
            return 0;
        }
    }

    /**
     * 预算的下限：固定开销 + 一小段历史。
     *
     * <p>比它更小的预算**在数学上不可能被满足**（固定开销无法压缩），
     * 保留它只会让仪表盘永远显示「超预算」，而请求照样发出去 ——
     * 那比直接把它抬到能成立的值更误导。
     */
    public int minimumBudgetTokens() {
        return fixedOverheadTokens() + MIN_HISTORY_TOKENS;
    }

    /** 注册固定开销的来源（幂等、无副作用的读操作）。 */
    public void registerFixedOverhead(java.util.function.IntSupplier supplier) {
        this.fixedOverhead = supplier == null ? () -> 0 : supplier;
    }

    /**
     * 在指定模型窗口下生效的预算（token）。
     *
     * <p>为什么要「收敛」而不是直接用 {@code budgetTokens}：预算是用户在某个
     * 模型下选的，换到窗口更小的模型后那个数可能超出模型能力。此时必须按
     * **模型的**窗口截断，否则请求会被端点拒绝（400 context length exceeded）。
     * 注意这里**不写回** {@code budgetTokens}：换回大窗口模型时用户原来的
     * 预算应当还在，被一次小模型访问永久改小是意外损失。
     *
     * @param modelName 当前会话使用的模型
     */
    public int budgetTokensFor(String modelName) {
        int window = contextWindowTokens(modelName);
        // 先抬到下限再按窗口截断：顺序不能反 —— 先截断再抬，会把下限顶到窗口之外
        int effective = Math.max(budgetTokens, minimumBudgetTokens());
        return Math.min(effective, window);
    }

    /**
     * 设置预算（token）。
     *
     * <p>收敛到 {@code [1, 模型窗口]}：超过模型窗口的预算没有意义
     * （模型根本吃不下），小于 1 会让窗口永远为空。
     *
     * <p>上界取**全局最大窗口**而不是某个模型的窗口：设置接口不知道用户
     * 之后会切到哪个模型，用某个具体模型的窗口当上界会让「给 1M 模型选 300K」
     * 在那一刻被错误地压到 32K。
     */
    public void setBudgetTokens(int tokens) {
        this.budgetTokens = Math.min(Math.max(tokens, 1), maxKnownWindow());
    }

    /**
     * 系统知道的**最大**窗口：全局默认与 1M 取大者。
     *
     * <p>用它做预算上界，比用「当前模型的窗口」宽松 —— 用户可以在 32K 模型下
     * 先选好 300K 的预算，切到 1M 模型时立即生效，不必再改一次。
     * 真正保护模型的是 {@link #budgetTokensFor(String)} 的截断。
     */
    private int maxKnownWindow() {
        return Math.max(contextWindowTokens(), 1_048_576);
    }

    /** 折算成字符预算 —— 窗口裁剪用的就是这个数 */
    public int effectiveCharBudget() {
        return (int) Math.round(budgetTokens * CHARS_PER_TOKEN);
    }

    /**
     * 在指定模型窗口下折算的字符预算（V25）。
     *
     * <p>这是窗口裁剪**真正**该用的值：换到窗口小的模型后，字符预算也要跟着降，
     * 否则「仪表盘显示占满了」而「实际还在往里塞」，请求直接被端点拒绝。
     *
     * <p><b>2026-10-29 起先扣掉固定开销</b>：预算的语义是「这一轮请求最多送多少 token」，
     * 而基础人设与工具定义（实测约 4K）无法通过裁历史去掉。不扣的话，
     * 预算 32K 实际会送出 36K，预算设成 2048 时更是必然超出 —— 用户设的数值
     * 与系统行为完全脱节。扣完留一小段历史（{@link #MIN_HISTORY_TOKENS}），
     * 保证预算极小时也不会退化成「一条历史都不带」。
     *
     * @param modelName 当前会话使用的模型名
     */
    public int effectiveCharBudgetFor(String modelName) {
        int usable = Math.max(budgetTokensFor(modelName) - fixedOverheadTokens(), MIN_HISTORY_TOKENS);
        return (int) Math.round(usable * CHARS_PER_TOKEN);
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
