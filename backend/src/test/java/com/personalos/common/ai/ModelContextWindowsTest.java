package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ModelContextWindows} 匹配规则单元测试。
 *
 * <p>守的是「模型名带前缀/后缀时内置表还能不能用」：
 * DashScope 把同一个模型同时列成 {@code qwen3.8-flash} 与 {@code cmaas-qwen3.8-flash}，
 * 聚合网关写成 {@code azure/gpt-4o}。只做前缀匹配时这些名字永远不中，
 * 于是内置表对这一大类模型形同不存在 —— 用户看到的正是
 * 「表里明明有，窗口还是 126K」。
 */
class ModelContextWindowsTest {

    @Test
    @DisplayName("前缀匹配：带版本后缀也能命中，且优先更长的键")
    void shouldMatchByPrefix() {
        assertThat(ModelContextWindows.lookup("qwen-max-latest")).isEqualTo(30_720);
        assertThat(ModelContextWindows.lookup("gpt-4o-2024-08-06")).isEqualTo(128_000);
    }

    @Test
    @DisplayName("包含匹配：厂商/中转前缀不影响命中")
    void shouldMatchByContains() {
        assertThat(ModelContextWindows.lookup("cmaas-qwen3.8-flash")).isEqualTo(131_072);
        assertThat(ModelContextWindows.lookup("cmaas-deepseek-v4.1-flash")).isEqualTo(1_048_576);
        assertThat(ModelContextWindows.lookup("azure/gpt-4o")).isEqualTo(128_000);
    }

    @Test
    @DisplayName("v4.1-flash 的后缀变体（-1017）也命中 1M")
    void shouldMatchDatedVariant() {
        assertThat(ModelContextWindows.lookup("deepseek-v4.1-flash-1017")).isEqualTo(1_048_576);
    }

    @Test
    @DisplayName("短键不做包含匹配：proto1 不应被当成 o1（200K）")
    void shouldNotMatchShortKeyInsideWord() {
        assertThat(ModelContextWindows.lookup("proto1")).isNull();
        assertThat(ModelContextWindows.lookup("xkimix")).isNull();
    }

    @Test
    @DisplayName("表里没有的模型返回 null，由全局默认兜底（而不是猜一个数）")
    void shouldReturnNullForUnknown() {
        assertThat(ModelContextWindows.lookup("totally-unknown-model")).isNull();
        assertThat(ModelContextWindows.lookup("")).isNull();
        assertThat(ModelContextWindows.lookup(null)).isNull();
    }

    @Test
    @DisplayName("归一化：下划线、大小写、冒号后缀都收敛")
    void shouldNormalize() {
        assertThat(ModelContextWindows.lookup("QWEN_MAX")).isEqualTo(30_720);
        assertThat(ModelContextWindows.lookup("qwen_max")).isEqualTo(30_720);
        // 冒号后是端点自定义标签（Ollama 的 llama-3.1:8b-instruct），截掉再匹配
        assertThat(ModelContextWindows.lookup("llama-3.1:8b-instruct")).isEqualTo(131_072);
    }
}
