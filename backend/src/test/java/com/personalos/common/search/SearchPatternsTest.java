package com.personalos.common.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SearchPatterns} 的契约。
 *
 * <p>这个类只有三行逻辑，但三行都是「写错就会静默搜不到东西」的类型：
 * 不转义 → 通配符生效；不转小写 → 含大写的关键词恒 0 结果。
 * 后者尤其难查，因为它只对部分输入失败。
 */
class SearchPatternsTest {

    @Test
    @DisplayName("两端补 %，并统一转小写")
    void shouldWrapAndLowerCase() {
        assertThat(SearchPatterns.likePattern("Vue")).isEqualTo("%vue%");
        assertThat(SearchPatterns.likePattern("PostgreSQL")).isEqualTo("%postgresql%");
        assertThat(SearchPatterns.likePattern("孤立")).isEqualTo("%孤立%");
    }

    @Test
    @DisplayName("首尾空白被忽略：从别处粘贴过来的关键词不该因此搜不到")
    void shouldStripSurroundingWhitespace() {
        assertThat(SearchPatterns.likePattern("  API  ")).isEqualTo("%api%");
    }

    @Test
    @DisplayName("通配符被转义：搜 100% 不该命中「100 之后任意字符」")
    void shouldEscapeWildcards() {
        assertThat(SearchPatterns.likePattern("100%")).isEqualTo("%100\\%%");
        assertThat(SearchPatterns.likePattern("a_b")).isEqualTo("%a\\_b%");
        // 转义符自身也要转义，否则反斜杠会把后面的字符吃掉
        assertThat(SearchPatterns.likePattern("C:\\tmp")).isEqualTo("%c:\\\\tmp%");
    }

    @Test
    @DisplayName("单独的通配符不会变成「匹配全库」：_ 仍按字面量匹配")
    void shouldNotTurnLoneWildcardIntoMatchAll() {
        assertThat(SearchPatterns.likePattern("_")).isEqualTo("%\\_%");
        assertThat(SearchPatterns.likePattern("%")).isEqualTo("%\\%%");
    }
}
