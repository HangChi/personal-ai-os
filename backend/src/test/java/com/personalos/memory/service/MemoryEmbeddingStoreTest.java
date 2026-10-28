package com.personalos.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link MemoryEmbeddingStore} 单元测试。
 *
 * <p>重点在两处「静默出错」的边界：
 * <ol>
 *   <li>向量字面量格式 —— 格式错不会报错，只会让相似度算出一个荒谬的值；</li>
 *   <li>缺参数时**不查库** —— 每次查询都带模型名过滤，缺了它就会跨模型比较。</li>
 * </ol>
 */
class MemoryEmbeddingStoreTest {

    private JdbcTemplate jdbcTemplate;
    private MemoryEmbeddingStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new MemoryEmbeddingStore(jdbcTemplate);
    }

    @Test
    @DisplayName("toLiteral(): 紧凑的 pgvector 字面量（无空格，方括号包裹）")
    void toLiteralShouldBeCompact() {
        assertThat(MemoryEmbeddingStore.toLiteral(new float[] {0.5f, -1.25f, 0f}))
                .isEqualTo("[0.5,-1.25,0.0]");
    }

    @Test
    @DisplayName("findSimilar(): 缺向量或模型名时不查库，直接判定「无重复」")
    void findSimilarShouldSkipWithoutVectorOrModel() {
        assertThat(store.findSimilar(null, "m", 0.9, null)).isEmpty();
        assertThat(store.findSimilar(new float[] {0.1f}, null, 0.9, null)).isEmpty();
        assertThat(store.findSimilar(new float[] {0.1f}, "   ", 0.9, null)).isEmpty();

        // 关键：不查库。跨模型比较比「不比较」危险得多 —— 两个空间的余弦值
        // 会给出一个看似合理、实际无意义的相似度
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("similarityTo(): 缺向量或模型名时返回空映射（不返回全 0，两者语义不同）")
    void similarityToShouldReturnEmptyMapWhenUnavailable() {
        assertThat(store.similarityTo(null, "m", 10)).isEmpty();
        assertThat(store.similarityTo(new float[] {0.1f}, "", 10)).isEmpty();
    }

    @Test
    @DisplayName("save(): 维度不符时返回 false 而不是抛异常 —— 记忆本身仍然可用")
    void saveShouldSwallowDimensionMismatch() {
        given(jdbcTemplate.update(anyString(), any(Object[].class)))
                .willThrow(new DataIntegrityViolationException("expected 1024 dimensions"));

        boolean saved = store.save(UUID.randomUUID(), new float[] {0.1f, 0.2f}, "test-model");

        assertThat(saved).isFalse();
    }

    @Test
    @DisplayName("save(): 空向量直接跳过（pgvector 对零长度向量的报错没有可读信息）")
    void saveShouldSkipEmptyVector() {
        assertThat(store.save(UUID.randomUUID(), new float[0], "m")).isFalse();
        assertThat(store.save(UUID.randomUUID(), null, "m")).isFalse();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("findSimilar(): 相似度低于阈值时视为不同事实（阈值是「是否合并」的唯一判据）")
    void findSimilarShouldRespectThreshold() {
        given(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)))
                .willReturn(java.util.List.of(new MemoryEmbeddingStore.SimilarMatch(UUID.randomUUID(), 0.71)));

        Optional<MemoryEmbeddingStore.SimilarMatch> match =
                store.findSimilar(new float[] {0.1f}, "test-model", 0.92, UUID.randomUUID());

        assertThat(match)
                .as("0.71 是「同一话题的不同事实」，合掉会丢信息")
                .isEmpty();
    }

    @Test
    @DisplayName("findSimilar(): 命中时返回条目与相似度")
    void findSimilarShouldReturnMatch() {
        UUID id = UUID.randomUUID();
        given(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class),
                any(Object[].class)))
                .willReturn(java.util.List.of(new MemoryEmbeddingStore.SimilarMatch(id, 0.97)));

        Optional<MemoryEmbeddingStore.SimilarMatch> match =
                store.findSimilar(new float[] {0.1f}, "test-model", 0.92, null);

        assertThat(match).isPresent();
        assertThat(match.get().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("findPending(): 无待补条目时返回空列表（回填任务据此结束，而不是空转）")
    void findPendingShouldReturnEmptyWhenNothingToDo() {
        assertThat(store.findPending(20)).isEmpty();
    }
}
