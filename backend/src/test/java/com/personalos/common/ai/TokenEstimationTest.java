package com.personalos.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字符 / token 折算（2026-10-27 实测标定后新增）。
 *
 * <p>断言里写死了标定得到的数字：中文 1.679 字/token、英文 5.698 字符/token。
 * 系数是<b>测</b>出来的，不是拍的 —— 改动它应当同时更新
 * {@code TokenEstimation} 的类注释与这里，否则就变成「谁改都行」的魔法数。
 */
class TokenEstimationTest {

    @Test
    @DisplayName("纯中文：按中文口径折算")
    void chineseUsesCjkRatio() {
        String text = "向量检索的本质是把语义压缩进高维空间".repeat(10);

        assertThat(TokenEstimation.charsPerToken(text)).isEqualTo(TokenEstimation.CJK_CHARS_PER_TOKEN);
        // 340 字 / 1.6 ≈ 213 token
        assertThat(TokenEstimation.toTokens(text, text.length()))
                .isEqualTo((int) Math.round(text.length() / 1.6));
    }

    @Test
    @DisplayName("纯英文：按拉丁口径折算，与中文口径差 3 倍以上")
    void englishUsesLatinRatio() {
        String text = "vector retrieval compresses meaning into a high dimensional space ".repeat(10);

        assertThat(TokenEstimation.charsPerToken(text)).isEqualTo(TokenEstimation.LATIN_CHARS_PER_TOKEN);
        int tokens = TokenEstimation.toTokens(text, text.length());
        int asIfChinese = TokenEstimation.toTokens(text.length(), TokenEstimation.CJK_CHARS_PER_TOKEN);
        assertThat(asIfChinese).as("用中文口径算英文会高估 3 倍以上").isGreaterThan(tokens * 3);
    }

    @Test
    @DisplayName("中英混排技术笔记：非 ASCII 占比高，走中文口径")
    void mixedTechnicalNoteUsesCjkRatio() {
        String text = "使用 Spring Boot 实现 SSE 流式输出（Server-Sent Events），"
                + "注意 produces 不能声明 text/event-stream，否则错误响应会被标成事件流。";

        assertThat(TokenEstimation.charsPerToken(text)).isEqualTo(TokenEstimation.CJK_CHARS_PER_TOKEN);
    }

    @Test
    @DisplayName("英文文档里少量非 ASCII（引号、破折号）不改变口径")
    void sparseNonAsciiStillLatin() {
        String text = "The quick brown fox jumps over the lazy dog — again, "
                + "\u201Cquoted\u201D and caf\u00E9. ".repeat(6);

        assertThat(TokenEstimation.charsPerToken(text)).isEqualTo(TokenEstimation.LATIN_CHARS_PER_TOKEN);
    }

    @Test
    @DisplayName("空文本与 null 走中文口径且折算为 0，不抛异常")
    void emptyInputsAreSafe() {
        assertThat(TokenEstimation.toTokens(null, 0)).isZero();
        assertThat(TokenEstimation.toTokens("", 0)).isZero();
        assertThat(TokenEstimation.toTokens("abc", -5)).isZero();
        assertThat(TokenEstimation.charsPerToken(null)).isEqualTo(TokenEstimation.CJK_CHARS_PER_TOKEN);
    }

    @Test
    @DisplayName("code point 计长：emoji 与扩展区汉字不被算成两个字符")
    void countsCodePointsNotChars() {
        // 两个代理项组成一个 emoji；若按 char 统计，非 ASCII 占比会虚高
        String text = "ok \uD83D\uDE00 ok";
        // 8 个 code point，其中 1 个非 ASCII → 12.5%，仍是拉丁口径
        assertThat(TokenEstimation.charsPerToken(text)).isEqualTo(TokenEstimation.LATIN_CHARS_PER_TOKEN);
    }
}
