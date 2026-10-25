package com.personalos.common.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolArguments} 单元测试：锁定工具层参数解析的共享规则。
 *
 * <p>{@code parseEnum} 把原先散在三个工具类里的枚举解析收敛成一处，因此要守住三件事：
 * 大小写不敏感、错误文案里带上允许值清单、以及「非必填时空值返回 null」——
 * 最后一条决定了模型省略参数的写法不会被判成错误。
 */
class ToolArgumentsTest {

    /** 测试用枚举：与任务优先级同形（全大写常量）。 */
    private enum Level { LOW, MEDIUM, HIGH }

    private static final String ALLOWED = "LOW / MEDIUM / HIGH";

    @Test
    @DisplayName("parseEnum(): 大小写不敏感，并去掉首尾空白")
    void parseEnumShouldBeCaseInsensitive() {
        assertThat(ToolArguments.parseEnum(Level.class, "HIGH", "优先级", ALLOWED, false))
                .isEqualTo(Level.HIGH);
        assertThat(ToolArguments.parseEnum(Level.class, "high", "优先级", ALLOWED, false))
                .isEqualTo(Level.HIGH);
        assertThat(ToolArguments.parseEnum(Level.class, "  Medium  ", "优先级", ALLOWED, false))
                .isEqualTo(Level.MEDIUM);
    }

    @Test
    @DisplayName("parseEnum(): 非必填时空值返回 null（模型的省略写法不算错误）")
    void parseEnumShouldReturnNullWhenOptionalAndBlank() {
        assertThat(ToolArguments.parseEnum(Level.class, null, "优先级", ALLOWED, false)).isNull();
        assertThat(ToolArguments.parseEnum(Level.class, "   ", "优先级", ALLOWED, false)).isNull();
    }

    @Test
    @DisplayName("parseEnum(): 必填且为空时抛出校验失败")
    void parseEnumShouldRejectBlankWhenRequired() {
        assertThatThrownBy(() -> ToolArguments.parseEnum(Level.class, null, "优先级", ALLOWED, true))
                .isInstanceOf(BusinessException.class)
                .hasMessage("优先级不能为空")
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("parseEnum(): 非法取值时错误文案包含允许值清单与原始输入")
    void parseEnumShouldRejectUnknownValue() {
        assertThatThrownBy(() -> ToolArguments.parseEnum(Level.class, "URGENT", "优先级", ALLOWED, false))
                .isInstanceOf(BusinessException.class)
                .hasMessage("优先级只能是 LOW / MEDIUM / HIGH，收到：URGENT");
    }
}
