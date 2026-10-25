package com.personalos.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TaskTags} 归一化单元测试。
 *
 * <p>这些边界每一条都能单独造成脏数据：漏去重会重复显示、漏去空会存进空标签、
 * 漏校验会在数据库层报长度错误（错误码难看且提示不知所云）。
 */
class TaskTagsTest {

    @Test
    @DisplayName("正常标签：保留顺序")
    void shouldKeepOrder() {
        assertThat(TaskTags.normalize(List.of("工作", "本周")))
                .containsExactly("工作", "本周");
    }

    @Test
    @DisplayName("去空白 + 去空 + 去重，且保留首次出现的顺序")
    void shouldNormalizeDuplicatesAndBlanks() {
        assertThat(TaskTags.normalize(Arrays.asList(" 工作 ", "工作", "", "   ", null, "学习")))
                .containsExactly("工作", "学习");
    }

    @Test
    @DisplayName("空输入返回空列表（不是 null）")
    void shouldReturnEmptyForNull() {
        assertThat(TaskTags.normalize(null)).isEmpty();
        assertThat(TaskTags.normalize(List.of())).isEmpty();
        assertThat(TaskTags.normalize(List.of("  ", ""))).isEmpty();
    }

    @Test
    @DisplayName("单个标签超过 20 字符：明确报错，不截断")
    void shouldRejectTooLongTag() {
        String longTag = "标".repeat(TaskTags.TAG_MAX_LENGTH + 1);

        assertThatThrownBy(() -> TaskTags.normalize(List.of(longTag)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能超过 " + TaskTags.TAG_MAX_LENGTH);
    }

    @Test
    @DisplayName("超过 5 个标签：明确报错，不静默丢弃")
    void shouldRejectTooManyTags() {
        List<String> six = List.of("a", "b", "c", "d", "e", "f");

        assertThatThrownBy(() -> TaskTags.normalize(six))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 " + TaskTags.MAX_TAGS);
    }

    @Test
    @DisplayName("恰好 20 字符与恰好 5 个：边界内允许")
    void shouldAllowBoundaryValues() {
        String exact = "标".repeat(TaskTags.TAG_MAX_LENGTH);

        assertThat(TaskTags.normalize(List.of(exact))).containsExactly(exact);
        assertThat(TaskTags.normalize(List.of("a", "b", "c", "d", "e"))).hasSize(TaskTags.MAX_TAGS);
    }

    @Test
    @DisplayName("去重后数量达标即可（6 个里重复 2 个 → 4 个唯一，允许）")
    void shouldCountUniqueTags() {
        assertThat(TaskTags.normalize(List.of("a", "a", "b", "b", "c", "c")))
                .containsExactly("a", "b", "c");
    }
}
