package com.personalos.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryProperties} 单元测试。
 *
 * <p>配置记录的默认值归一必须被测：Spring Boot 的构造器绑定一旦因为
 * 「多了一个构造器」而挑错，字段会**静默不绑定**（`AiProperties.Memory` 上踩过），
 * 症状是「配置不生效」而不是启动报错。这里守住的是「非法值被收敛」这一层。
 */
class MemoryPropertiesTest {

    @Test
    @DisplayName("缺省与非法值都被收敛到安全默认，不会把 0 当成有效配置")
    void shouldNormalizeInvalidValues() {
        MemoryProperties properties = new MemoryProperties(
                0, 0, new MemoryProperties.Ranking(true, null, -5), new MemoryProperties.Backfill(true, 0));

        assertThat(properties.pinnedMaxCount()).isEqualTo(10);
        assertThat(properties.similarThreshold()).isEqualTo(0.92);
        assertThat(properties.ranking().timeout()).isEqualTo(Duration.ofMillis(400));
        assertThat(properties.ranking().halfLifeDays()).isEqualTo(30);
        assertThat(properties.backfill().batchSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("嵌套配置为 null 时补出可用默认值（而不是 NPE）")
    void shouldFillNestedDefaults() {
        MemoryProperties properties = new MemoryProperties(5, 0.95, null, null);

        assertThat(properties.ranking()).isNotNull();
        assertThat(properties.ranking().enabled()).isTrue();
        assertThat(properties.backfill()).isNotNull();
        assertThat(properties.backfill().enabled()).isTrue();
    }

    @Test
    @DisplayName("相似度阈值必须落在 (0,1]：0 会把任何条目判成重复")
    void shouldRejectOutOfRangeThreshold() {
        assertThat(new MemoryProperties(10, 1.5, null, null).similarThreshold()).isEqualTo(0.92);
        assertThat(new MemoryProperties(10, -0.1, null, null).similarThreshold()).isEqualTo(0.92);
        assertThat(new MemoryProperties(10, 1.0, null, null).similarThreshold()).isEqualTo(1.0);
    }
}
