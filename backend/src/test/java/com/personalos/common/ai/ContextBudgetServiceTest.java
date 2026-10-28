package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文窗口的三级回落与按模型收敛预算（V25）。
 *
 * <h2>为什么这些断言值得单独立一个测试类</h2>
 * 「窗口」是这个功能里唯一一个**同时影响两个地方**的数：仪表盘的分母，
 * 以及实际裁剪的阈值。两者若不同源，症状是「界面显示才用了 30%，
 * 请求却被端点拒绝」。所以这里刻意逐档验证回落顺序，而不是只测「能取到值」。
 */
@DisplayName("ContextBudgetService 的按模型窗口")
class ContextBudgetServiceTest {

    /** 测试用配置：全局窗口 128K，与 {@code AiProperties.Memory} 的默认一致 */
    private static AiProperties props(int globalWindow) {
        return new AiProperties(new AiProperties.Memory(20, globalWindow), null);
    }

    // ------------------------------------------------------------------ 三级回落

    @Test
    @DisplayName("配置里填了窗口：优先用它，压过内置表")
    void configWinsOverBuiltInTable() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        // 内置表里 qwen-max 是 30720（30K 输入）；配置里改成 64K 后应以配置为准
        service.registerWindowLookup(name -> "qwen-max".equals(name) ? 65_536 : null);

        assertThat(service.contextWindowTokens("qwen-max")).isEqualTo(65_536);
    }

    @Test
    @DisplayName("配置里没填（null）：用内置对照表")
    void builtInTableWhenConfigMissing() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.registerWindowLookup(name -> null);

        // 这三个值都来自 ModelContextWindows，与全局默认 128K 不同，
        // 能证明「真的查了表」而不是碰巧回落到默认值。
        // qwen-max 是 30720 而非 32768：后者是「上下文总长」，其中 2048
        // 留给输出，预算分母要的是「最多能塞多少输入」（见 ModelContextWindows）
        assertThat(service.contextWindowTokens("qwen-max")).isEqualTo(30_720);
        assertThat(service.contextWindowTokens("gpt-4o")).isEqualTo(128_000);
        assertThat(service.contextWindowTokens("qwen-long")).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("配置与内置表都不认识：回落到全局默认")
    void globalDefaultWhenAllUnknown() {
        ContextBudgetService service = new ContextBudgetService(props(200_000));
        service.registerWindowLookup(name -> null);

        assertThat(service.contextWindowTokens("my-private-finetune-v7")).isEqualTo(200_000);
    }

    @Test
    @DisplayName("模型名为 null/空白：直接给全局默认，不去查表")
    void blankModelUsesGlobalDefault() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));

        assertThat(service.contextWindowTokens(null)).isEqualTo(131_072);
        assertThat(service.contextWindowTokens("   ")).isEqualTo(131_072);
    }

    @Test
    @DisplayName("查询器抛异常时降级，而不是让聊天挂掉")
    void lookupFailureFallsBack() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.registerWindowLookup(name -> {
            throw new IllegalStateException("数据库抖了一下");
        });

        // 关键：不抛出去。qwen-max 在内置表里，应当拿到 30K
        assertThat(service.contextWindowTokens("qwen-max")).isEqualTo(30_720);
        // 完全不认识的模型才回落到全局默认
        assertThat(service.contextWindowTokens("unknown-model")).isEqualTo(131_072);
    }

    @Test
    @DisplayName("配置里给了一个不合理的小值（< 1024）时当作无效，继续回落")
    void implausibleConfigValueIgnored() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.registerWindowLookup(name -> 100);

        assertThat(service.contextWindowTokens("qwen-max")).isEqualTo(30_720);
    }

    // ------------------------------------------------------------------ 预算收敛

    @Test
    @DisplayName("预算按模型窗口收敛：大预算落到小窗口模型时被截断")
    void budgetClampedByModelWindow() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.setBudgetTokens(300_000);

        // 30K 窗口的模型下，300K 预算必须被压到 30K
        assertThat(service.budgetTokensFor("qwen-max")).isEqualTo(30_720);
        // 同一个预算在 1M 窗口的模型下不被压
        assertThat(service.budgetTokensFor("qwen-long")).isEqualTo(300_000);
    }

    @Test
    @DisplayName("收敛不写回：换回大窗口模型后原预算还在")
    void clampingDoesNotMutateStoredBudget() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.setBudgetTokens(300_000);

        service.budgetTokensFor("qwen-max"); // 一次小窗口访问

        // 原预算没被永久改小 —— 否则用户「切一下小模型再切回来」就悄悄丢了配置
        assertThat(service.budgetTokens()).isEqualTo(300_000);
        assertThat(service.budgetTokensFor("qwen-long")).isEqualTo(300_000);
    }

    @Test
    @DisplayName("设置预算的上界取 1M，不因当前全局窗口小而压死")
    void setBudgetAllowsUpToLargeWindow() {
        ContextBudgetService service = new ContextBudgetService(props(32_768));
        service.setBudgetTokens(500_000);

        // 全局默认只有 32K，但用户可能之后切到 1M 的模型，
        // 此刻就把预算压到 32K 会让他必须再改一次
        assertThat(service.budgetTokens()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("设置预算的下界是 1：0 或负值会让窗口永远为空")
    void setBudgetLowerBound() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));

        service.setBudgetTokens(0);
        assertThat(service.budgetTokens()).isEqualTo(1);

        service.setBudgetTokens(-5);
        assertThat(service.budgetTokens()).isEqualTo(1);
    }

    @Test
    @DisplayName("字符预算跟着模型窗口走，与 token 预算是同一口径")
    void charBudgetFollowsModelWindow() {
        ContextBudgetService service = new ContextBudgetService(props(131_072));
        service.setBudgetTokens(300_000);

        int small = service.effectiveCharBudgetFor("qwen-max");
        int large = service.effectiveCharBudgetFor("qwen-long");

        assertThat(small).isLessThan(large);
        // 逆运算能还原回去，证明两条路径用的是同一个折算比
        assertThat((int) Math.round(small / ContextBudgetService.CHARS_PER_TOKEN)).isEqualTo(30_720);
    }

    // ------------------------------------------------------------------ 内置对照表

    @Test
    @DisplayName("内置对照表按前缀匹配，且更长的键优先")
    void lookupPrefersLongerPrefix() {
        // qwen-max（30720）与 qwen3-max（262144）是各自独立的键；
        // 这里真正要防的是 qwen-long 被 qwen-* 之类更短的键抢先命中
        assertThat(ModelContextWindows.lookup("qwen-long")).isEqualTo(1_000_000);
        assertThat(ModelContextWindows.lookup("qwen-max")).isEqualTo(30_720);
        assertThat(ModelContextWindows.lookup("qwen-max-latest")).isEqualTo(30_720);
    }

    @Test
    @DisplayName("模型名大小写与下划线写法不同也能认")
    void lookupNormalizesName() {
        assertThat(ModelContextWindows.lookup("GPT-4o")).isEqualTo(128_000);
        assertThat(ModelContextWindows.lookup("qwen_max")).isEqualTo(30_720);
    }

    @Test
    @DisplayName("Ollama 的 :tag 后缀不影响匹配")
    void lookupStripsOllamaTag() {
        assertThat(ModelContextWindows.lookup("llama-3.1:70b")).isEqualTo(131_072);
    }

    @Test
    @DisplayName("表里没有的模型返回 null，而不是 0")
    void lookupUnknownReturnsNull() {
        // 0 会被当成合法读数拿去算占比（除以 0 得到 Infinity），必须区分「未知」
        assertThat(ModelContextWindows.lookup("totally-unknown-model")).isNull();
        assertThat(ModelContextWindows.lookup(null)).isNull();
        assertThat(ModelContextWindows.lookup("")).isNull();
    }

    @Test
    @DisplayName("内置表的所有值都在合理区间内（防止手滑写错数量级）")
    void allKnownWindowsArePlausible() {
        // 逐个抽查代表性模型，覆盖各家的最小与最大档
        Map<String, Integer> samples = Map.of(
                "gpt-4", 8_192,
                "gpt-4-32k", 32_768,
                "claude-3-5-sonnet", 200_000,
                "gemini-1.5-pro", 2_097_152,
                "deepseek-chat", 65_536);
        samples.forEach((name, expected) ->
                assertThat(ModelContextWindows.lookup(name))
                        .as("模型 %s 的窗口", name)
                        .isEqualTo(expected));
    }
}
