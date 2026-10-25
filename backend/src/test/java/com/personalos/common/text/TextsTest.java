package com.personalos.common.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Texts} 单元测试：锁定「空值归一」与「必填 + 长度上限」两条共享规则。
 *
 * <p>这两条规则原本在任务与日程两条写路径上各有一份实现。抽成共享代码后，
 * 边界与错误文案必须被测试固定下来 —— 否则下次有人改了长度上限，
 * 两侧会一起漂移而没有任何东西会失败。
 */
class TextsTest {

    @Test
    @DisplayName("trimToNull(): null 与全空白都归一为 null")
    void trimToNullShouldCollapseBlankToNull() {
        assertThat(Texts.trimToNull(null)).isNull();
        assertThat(Texts.trimToNull("")).isNull();
        assertThat(Texts.trimToNull("   ")).isNull();
        assertThat(Texts.trimToNull("\t\n")).isNull();
    }

    @Test
    @DisplayName("trimToNull(): 只去掉首尾空白，中间空白保留")
    void trimToNullShouldStripEndsOnly() {
        assertThat(Texts.trimToNull(" 交周报 ")).isEqualTo("交周报");
        assertThat(Texts.trimToNull("交 周 报")).isEqualTo("交 周 报");
    }

    @Test
    @DisplayName("requireNotBlank(): 空值抛出校验失败，文案为「字段名不能为空」")
    void requireNotBlankShouldRejectBlank() {
        assertThatThrownBy(() -> Texts.requireNotBlank(null, "标题", Texts.TITLE_MAX_LENGTH))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标题不能为空")
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);

        assertThatThrownBy(() -> Texts.requireNotBlank("   ", "标题", Texts.TITLE_MAX_LENGTH))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标题不能为空");
    }

    @Test
    @DisplayName("requireNotBlank(): 通过校验时返回去空白后的文本")
    void requireNotBlankShouldReturnStripped() {
        assertThat(Texts.requireNotBlank("  交周报  ", "标题", Texts.TITLE_MAX_LENGTH))
                .isEqualTo("交周报");
    }

    @Test
    @DisplayName("requireNotBlank(): 长度按去空白后计算，正好等于上限时通过")
    void requireNotBlankShouldAcceptExactLimit() {
        String exactlyLimit = "a".repeat(Texts.TITLE_MAX_LENGTH);

        assertThat(Texts.requireNotBlank("  " + exactlyLimit + "  ", "标题", Texts.TITLE_MAX_LENGTH))
                .hasSize(Texts.TITLE_MAX_LENGTH);
    }

    @Test
    @DisplayName("requireNotBlank(): 超出上限时抛出校验失败，文案含上限数字")
    void requireNotBlankShouldRejectOverLimit() {
        String tooLong = "a".repeat(Texts.TITLE_MAX_LENGTH + 1);

        assertThatThrownBy(() -> Texts.requireNotBlank(tooLong, "标题", Texts.TITLE_MAX_LENGTH))
                .isInstanceOf(BusinessException.class)
                .hasMessage("标题长度不能超过 200");
    }
}
