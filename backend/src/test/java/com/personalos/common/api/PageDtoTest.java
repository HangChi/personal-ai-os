package com.personalos.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PageDto} 的切片语义。
 *
 * <p>重点覆盖「越界但不该报错」的情况：分页参数完全由客户端控制，
 * 任何一处算错都应该退化成空页，而不是把 500 抛给用户。
 */
class PageDtoTest {

    private static final List<Integer> ALL = IntStream.rangeClosed(1, 25).boxed().toList();

    @Test
    @DisplayName("常规切片：首页、中间页、末页（不足一页时按实际条数）")
    void shouldSlicePages() {
        assertThat(PageDto.of(ALL, 0, 10).items()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(PageDto.of(ALL, 1, 10).items()).containsExactly(11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        assertThat(PageDto.of(ALL, 2, 10).items()).containsExactly(21, 22, 23, 24, 25);
        assertThat(PageDto.of(ALL, 0, 10).total()).isEqualTo(25);
    }

    @Test
    @DisplayName("页码乘以每页条数用 int 会溢出：超大 page 必须返回空页，而不是负下标")
    void shouldNotOverflowOnHugePage() {
        // 21474837 * 100 = 2147483700，超过 Integer.MAX_VALUE。
        // 用 int 计算时 from 变成负数，`from >= all.size()` 判不出来，
        // subList(负数, ...) 直接抛 IndexOutOfBoundsException —— 一条构造出来的
        // 查询参数（?page=21474837&size=100）就能稳定打出 500。
        PageDto<Integer> page = PageDto.of(ALL, 21_474_837, 100);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(25);
        assertThat(page.page()).isEqualTo(21_474_837);
        assertThat(page.size()).isEqualTo(100);
    }

    @Test
    @DisplayName("页码越界（非溢出）也返回空页")
    void shouldReturnEmptyPageBeyondEnd() {
        assertThat(PageDto.of(ALL, 99, 10).items()).isEmpty();
        assertThat(PageDto.of(ALL, 99, 10).total()).isEqualTo(25);
    }

    @Test
    @DisplayName("参数收敛：负页码按 0、非正 size 按 1")
    void shouldNormalizeIllegalArguments() {
        PageDto<Integer> negativePage = PageDto.of(ALL, -5, 10);
        assertThat(negativePage.page()).isZero();
        assertThat(negativePage.items()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

        PageDto<Integer> zeroSize = PageDto.of(ALL, 0, 0);
        assertThat(zeroSize.size()).isEqualTo(1);
        assertThat(zeroSize.items()).containsExactly(1);
    }

    @Test
    @DisplayName("空列表：任何页码都返回空页，total 为 0")
    void shouldHandleEmptySource() {
        PageDto<Integer> page = PageDto.of(List.of(), 0, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
