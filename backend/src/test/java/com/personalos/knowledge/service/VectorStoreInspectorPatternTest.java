package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 关键词通道的正则构造（混合检索）。
 *
 * <p>这里守两件事：
 * <ol>
 *   <li><b>词首匹配</b>：子串匹配会让 {@code io} 命中 {@code version}、{@code configuration}，
 *       实测 233 个片段里命中 118 个（51%），噪声直接挤掉正确答案；</li>
 *   <li><b>元字符转义</b>：{@code C++}、{@code Object.defineProperty} 里的 {@code +} / {@code .}
 *       不转义会被当成量词和通配符，{@code Object.defineProperty} 会退化成匹配一切。</li>
 * </ol>
 */
class VectorStoreInspectorPatternTest {

    @Test
    @DisplayName("词首锚定：{@code \\m} 开头")
    void shouldAnchorAtWordStart() {
        assertThat(VectorStoreInspector.wordStartPattern("io")).isEqualTo("\\mio");
        assertThat(VectorStoreInspector.wordStartPattern("unknown")).isEqualTo("\\munknown");
    }

    @Test
    @DisplayName("正则元字符必须转义，否则会退化成通配")
    void shouldEscapeRegexMetacharacters() {
        // `.` 不转义会匹配任意字符 —— `Object.defineProperty` 会命中几乎任何含 Object 的文本
        assertThat(VectorStoreInspector.wordStartPattern("object.defineproperty"))
                .isEqualTo("\\mobject\\.defineproperty");
        // `+` 不转义是量词: C+ 会匹配 C、CC、CCC
        assertThat(VectorStoreInspector.wordStartPattern("c++")).isEqualTo("\\mc\\+\\+");
        assertThat(VectorStoreInspector.wordStartPattern("c#")).isEqualTo("\\mc#");
        // 下划线不是元字符，不能被转义（否则 ip_hash 永远匹配不上）
        assertThat(VectorStoreInspector.wordStartPattern("ip_hash")).isEqualTo("\\mip_hash");
        // 括号与方括号成对转义
        assertThat(VectorStoreInspector.wordStartPattern("f(x)")).isEqualTo("\\mf\\(x\\)");
    }

    @Test
    @DisplayName("数字开头的标识符照常锚定（2PC 不能被截断成 PC）")
    void numericLeadingToken() {
        assertThat(VectorStoreInspector.wordStartPattern("2pc")).isEqualTo("\\m2pc");
    }
}
