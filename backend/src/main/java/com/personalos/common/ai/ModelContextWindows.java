package com.personalos.common.ai;

import java.util.Locale;
import java.util.Map;

/**
 * 常见模型的上下文窗口对照表（V25）。
 *
 * <h2>为什么需要一张内置表</h2>
 * 「这个模型支持多长上下文」通常只有三个来源，按可靠性排序：
 * <ol>
 *   <li><b>端点自报</b>：{@code /models} 响应里的 {@code context_length} /
 *       {@code max_model_len} 等字段。最准，但覆盖率很低 —— 原生 OpenAI
 *       与 Anthropic 的接口**都不返回**这个字段，只有 vLLM、Ollama、
 *       部分中转站会带。</li>
 *   <li><b>内置对照表</b>（本类）：覆盖主流商业模型，靠模型名匹配。</li>
 *   <li><b>用户手填</b>：最终兜底，也是唯一能覆盖自建/微调模型的方式。</li>
 * </ol>
 * 三者按上述顺序合并：探测到的值 > 内置表 > 全局默认。
 *
 * <h2>为什么是前缀匹配 + 从长到短</h2>
 * 模型名带版本/尺寸后缀（{@code gpt-4o-2024-08-06}、{@code qwen-max-latest}），
 * 精确匹配几乎永远不中。前缀匹配能覆盖这些变体，但要注意
 * <b>先匹配更长的键</b>：{@code qwen-turbo} 与 {@code qwen-turbo-latest} 若都入表，
 * 必须让更长的那个先命中，否则永远只会用到短的。
 *
 * <h2>这张表会过期</h2>
 * 它只是一个「比猜准一点」的起点，不是权威数据源。因此：
 * <ul>
 *   <li>界面上明确标注这个值可以改，用户能覆盖它；</li>
 *   <li>不因为「表里没有」就拒绝工作 —— 返回 null，调用方回落到全局默认。</li>
 * </ul>
 */
public final class ModelContextWindows {

    /**
     * 模型名（小写前缀）→ 上下文窗口（token）。
     *
     * <p>数值取自各家公开文档。刻意只收录**窗口明确**的模型：
     * 拿不准的不写，让用户手填，比写一个错的强。
     */
    private static final Map<String, Integer> KNOWN = Map.ofEntries(
            // ---- OpenAI ----
            Map.entry("gpt-4.1", 1_047_576),
            Map.entry("gpt-4o-mini", 128_000),
            Map.entry("gpt-4o", 128_000),
            Map.entry("gpt-4-turbo", 128_000),
            Map.entry("gpt-4-32k", 32_768),
            Map.entry("gpt-4", 8_192),
            Map.entry("gpt-3.5-turbo", 16_385),
            Map.entry("o3", 200_000),
            Map.entry("o4-mini", 200_000),
            Map.entry("o1", 200_000),

            // ---- Anthropic ----
            Map.entry("claude-3-7", 200_000),
            Map.entry("claude-3-5", 200_000),
            Map.entry("claude-3", 200_000),
            Map.entry("claude-sonnet-4", 200_000),
            Map.entry("claude-opus-4", 200_000),

            // ---- 阿里通义（DashScope）----
            // qwen-max 写 30720 而不是文档口径的 32768：32768 是「上下文总长」，
            // 其中 2048 留给输出。预算分母要的是「最多能塞多少输入」，
            // 用 32768 会把预算放到端点上必然 400 的位置。
            // 实测（2026-10-28，DashScope 兼容模式）：超长时报
            // 「Range of input length should be [1, 30720]」，与
            // GET /models/qwen-max 的 extra_info.default_envs.max_input_tokens 一致。
            Map.entry("qwen-long", 1_000_000),
            Map.entry("qwen3-max", 262_144),
            Map.entry("qwen-max", 30_720),
            Map.entry("qwen-plus", 131_072),
            Map.entry("qwen-turbo", 131_072),
            Map.entry("qwen3", 131_072),
            Map.entry("qwen2.5", 131_072),
            Map.entry("qwen-vl-max", 30_720),

            // ---- DeepSeek ----
            Map.entry("deepseek-v3", 65_536),
            Map.entry("deepseek-chat", 65_536),
            Map.entry("deepseek-reasoner", 65_536),
            Map.entry("deepseek-coder", 65_536),

            // ---- Google ----
            Map.entry("gemini-2.5", 1_048_576),
            Map.entry("gemini-2.0", 1_048_576),
            Map.entry("gemini-1.5-pro", 2_097_152),
            Map.entry("gemini-1.5", 1_048_576),

            // ---- Meta / 开源 ----
            Map.entry("llama-3.3", 131_072),
            Map.entry("llama-3.1", 131_072),
            Map.entry("llama-3", 8_192),
            Map.entry("mistral-large", 131_072),

            // ---- 智谱 ----
            Map.entry("glm-4", 131_072),
            Map.entry("glm-4-plus", 131_072),

            // ---- Moonshot ----
            Map.entry("moonshot-v1-128k", 131_072),
            Map.entry("moonshot-v1-32k", 32_768),
            Map.entry("moonshot-v1-8k", 8_192),
            Map.entry("kimi", 131_072));

    private ModelContextWindows() {
    }

    /**
     * 按模型名查内置窗口。
     *
     * @param modelName 模型名；大小写与常见分隔符（{@code _} / {@code :}）都会被归一
     * @return 窗口 token；未知模型返回 null（调用方回落到全局默认）
     */
    public static Integer lookup(String modelName) {
        if (modelName == null) {
            return null;
        }
        String normalized = normalize(modelName);
        if (normalized.isEmpty()) {
            return null;
        }
        // 前缀匹配，且**优先更长的键**：qwen-max 与 qwen-max-latest 只能有一个命中答案，
        // 让更具体的那个先中。Map.ofEntries 的迭代顺序不保证，所以这里显式排序。
        return KNOWN.entrySet().stream()
                .filter(entry -> normalized.startsWith(entry.getKey()))
                .min((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
                .map(Map.Entry::getValue)
                .orElse(null);
    }

    /**
     * 归一化模型名：小写、下划线转连字符。
     *
     * <p>不同端点对同一个模型写法不同（{@code qwen_max} / {@code qwen-max}），
     * 归一后只需在表里写一种形式。冒号之后通常是端点自定义的标签
     * （Ollama 的 {@code llama3:8b-instruct}），截掉后再匹配。
     */
    private static String normalize(String raw) {
        String value = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        int colon = value.indexOf(':');
        if (colon > 0) {
            // 保留冒号前的主体：「llama3:8b」应命中 llama 系列而不是完全匹配
            value = value.substring(0, colon);
        }
        return value;
    }
}
