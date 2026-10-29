package com.personalos.common.ai;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 工具定义（JSON Schema）的固定开销 —— 每轮请求都会带上，但**内容侧估算是看不见它的**。
 *
 * <h2>为什么需要它</h2>
 * 2026-10-29 实测：一轮「只回复 OK」的短对话，字符侧估算 103 token，
 * 而上游返回的 {@code prompt_tokens} 是 4116 —— 差值几乎全部来自 23 个工具的
 * schema（名字 + 描述 + 参数结构）。这带来两个用户可见的矛盾：
 *
 * <ul>
 *   <li><b>仪表盘看着很空</b>：显示「103 / 32K」，用户以为还能聊很久，
 *       实际每次请求已经送出 4K；</li>
 *   <li><b>预算形同虚设</b>：把预算设成 2048，历史被裁到只剩 1 条，
 *       但请求仍然要 4K —— 预算管的是历史，管不到这份固定开销。</li>
 * </ul>
 *
 * <h2>为什么是「一次算、长期缓存」</h2>
 * 工具集合由各业务模块的 Bean 在启动期贡献，不随模型切换而改变
 * （见 {@code AiChatClientProvider} 的注释）。逐个工具取 schema 再拼长度
 * 在每次请求里做一遍没有意义，而且 MCP 的 {@code getToolCallbacks()} 不保证是零成本。
 *
 * <h2>算不准的代价是「偏差」而不是「错误」</h2>
 * 这里量的是**字符数**再按 1.6 字/token 折算，与上游 tokenizer 必然有偏差；
 * 它的用途是把「4K 的固定开销被算成 0」改成「大致算对」，而不是替代真实用量。
 * 有真实 {@code usage} 时一切以真实值为准（见 {@code ChatContextUsageDto.estimated}）。
 */
@Component
public class ToolSchemaOverhead {

    private static final Logger log = LoggerFactory.getLogger(ToolSchemaOverhead.class);

    private final ObjectProvider<ToolContributor> contributors;
    private final ObjectProvider<ToolCallbackProvider> callbackProviders;

    /**
     * 缓存的字符数；{@code -1} 表示还没算过。
     *
     * <p>用 {@code volatile} + 双重检查而不是 {@code synchronized} 方法：
     * 它在每轮对话的装配路径上被读一次，读多写一，锁只会让「聊天慢」多一个嫌疑对象。
     * 重复计算一次也只是浪费一次遍历，没有正确性问题。
     */
    private volatile int cachedChars = -1;

    public ToolSchemaOverhead(ObjectProvider<ToolContributor> contributors,
                              ObjectProvider<ToolCallbackProvider> callbackProviders) {
        this.contributors = contributors;
        this.callbackProviders = callbackProviders;
    }

    /** 工具定义的总字符数（名字 + 描述 + 参数 schema）。 */
    public int chars() {
        int value = cachedChars;
        if (value >= 0) {
            return value;
        }
        value = compute();
        cachedChars = value;
        return value;
    }

    /**
     * 工具定义折算成的 token 数。
     *
     * <h2>为什么这里**不**套用 {@link TokenEstimation} 的拉丁口径（2026-10-29 实测）</h2>
     * 工具 schema 以 ASCII 为主，看起来该用 {@code LATIN_CHARS_PER_TOKEN = 5.5}，
     * 但那是「英文散文」的标定 —— 散文里大量是长单词，tokenizer 的 BPE 合并率高。
     * JSON schema 恰恰相反：{@code {"type":"object","properties":{...}}} 里
     * 引号、冒号、逗号、括号密集，每个标点基本各占一个 token。
     *
     * <p>实测同一份工具集（22 个，压缩后 8415 字符）：
     * <ul>
     *   <li>按中文口径 1.6 → 5259 token，与真实 {@code prompt_tokens}(4077) 同量级，高估约 29%；</li>
     *   <li>按拉丁口径 5.5 → 1530 token，低估 60% 以上 —— 而低估的后果是**预算被撑爆**。</li>
     * </ul>
     * 也就是说 1.6 在这里既不是「中文口径」也不是巧合，它接近 JSON 的真实密度（约 2.4 字符/token，
     * 偏保守取更小的比值）。<b>谁想「修正」成 5.5，请先拿真实 {@code prompt_tokens} 复核这一条。</b>
     *
     * <p>方向刻意偏保守：宁可让用户以为快满了（会去看仪表盘、会调预算），
     * 也不要显示假的宽裕（请求被端点拒绝，而界面上还有大片余量）。
     * 有真实 {@code usage} 时一律以真实值为准，本值只在「还没有真实用量」时顶上。
     */
    public int tokens() {
        return ContextBudgetService.toTokens(chars());
    }

    /**
     * 遍历工具集合并累加定义长度。
     *
     * <p><b>任何异常都返回 0 而不是抛出</b>：这个数字只影响「仪表盘准不准」，
     * 让一次 schema 读取失败把整条对话打挂，是用一个大故障换一个小数值。
     */
    private int compute() {
        try {
            List<ToolCallback> callbacks = new ArrayList<>();
            // @Tool 标注的普通对象先包成回调，才能拿到 ToolDefinition
            contributors.orderedStream().forEach(contributor -> {
                List<Object> objects = contributor.toolObjects();
                if (objects == null || objects.isEmpty()) {
                    return;
                }
                callbacks.addAll(List.of(ToolCallbacks.from(objects.toArray())));
            });
            // MCP 等已经是回调的，原样使用
            callbackProviders.orderedStream()
                    .forEach(provider -> callbacks.addAll(List.of(provider.getToolCallbacks())));

            int total = 0;
            for (ToolCallback callback : callbacks) {
                ToolDefinition definition = callback.getToolDefinition();
                if (definition == null) {
                    continue;
                }
                total += lengthOf(definition.name())
                        + lengthOf(definition.description())
                        // schema 必须按**压缩后**算：inputSchema() 给的是带缩进与换行的
                        // pretty-print 文本，实际发出去的是紧凑 JSON。按原文算会虚高
                        // 约 1.6 倍（实测 10790 字符 vs 真实约 6500），而虚高的代价不是
                        // 「显示大一点」，是历史预算被白白削掉两千多 token。
                        + compactLength(definition.inputSchema());
            }
            log.info("工具定义固定开销已测算：{} 个工具 ≈ {} 字符 ≈ {} token",
                    callbacks.size(), total, ContextBudgetService.toTokens(total));
            return total;
        } catch (RuntimeException ex) {
            log.warn("工具定义开销测算失败，按 0 处理（只影响仪表盘精度）：{}", ex.toString());
            return 0;
        }
    }

    private static int lengthOf(String text) {
        return text == null ? 0 : text.length();
    }

    /**
     * JSON 压缩后的长度：去掉字符串字面量之外的空白。
     *
     * <p>为什么不用 {@code replaceAll("\\s+", "")}：那会把字符串字面量里的空格也删掉，
     * 得到的是「另一个文档」的长度 —— 而参数 schema 的描述里空格很常见。
     * 这里手动扫描一次，保证只在串外删空白，代价是几十行逻辑换回一个可比的数。
     */
    static int compactLength(String json) {
        if (json == null || json.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                count++;
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                count++;
            } else if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                count++;
            }
        }
        return count;
    }
}
