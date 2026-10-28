package com.personalos.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.personalos.memory.service.MemoryEmbeddingService;
import com.personalos.memory.service.MemoryEmbeddingStore;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MemoryEmbeddingBackfill} 单元测试。
 *
 * <p>这个任务的危险不是「补不上」，而是**死循环**：{@code findPending} 按
 * {@code embedded_at IS NULL} 取数据，某些条目会因为维度不符永远补不上，
 * 于是每次查询都返回同一批。所以「零进展即退出」是必须被守住的边界。
 */
class MemoryEmbeddingBackfillTest {

    private MemoryEmbeddingService embeddingService;
    private MemoryEmbeddingStore store;
    private MemoryEmbeddingBackfill backfill;

    @BeforeEach
    void setUp() {
        embeddingService = mock(MemoryEmbeddingService.class);
        store = mock(MemoryEmbeddingStore.class);
        MemoryProperties properties = new MemoryProperties(10, 0.92,
                new MemoryProperties.Ranking(false, null, 30),
                new MemoryProperties.Backfill(true, 2));
        backfill = new MemoryEmbeddingBackfill(properties, embeddingService, store);
        given(embeddingService.modelTag()).willReturn("test-embedding");
    }

    private static MemoryEmbeddingStore.PendingEmbedding pending(String content) {
        return new MemoryEmbeddingStore.PendingEmbedding(UUID.randomUUID(), content);
    }

    @Test
    @DisplayName("backfill(): 没有待补条目时不调用嵌入服务")
    void shouldDoNothingWhenNoPending() {
        given(store.findPending(anyInt())).willReturn(List.of());

        assertThat(backfill.backfill()).isZero();
        verify(embeddingService, never()).embed(anyString());
    }

    @Test
    @DisplayName("backfill(): 补完一批后继续，直到没有待补条目")
    void shouldProcessUntilEmpty() {
        MemoryEmbeddingStore.PendingEmbedding first = pending("周报周五交");
        MemoryEmbeddingStore.PendingEmbedding second = pending("回复用中文");
        given(store.findPending(2)).willReturn(List.of(first, second), List.of());
        given(embeddingService.embed(anyString())).willReturn(Optional.of(new float[] {0.1f, 0.2f}));
        given(store.save(any(), any(), anyString())).willReturn(true);

        assertThat(backfill.backfill()).isEqualTo(2);
    }

    @Test
    @DisplayName("backfill(): 嵌入连续失败时提前收手，不把日志刷满")
    void shouldStopAfterConsecutiveFailures() {
        given(store.findPending(2)).willReturn(
                List.of(pending("a"), pending("b"), pending("c")));
        given(embeddingService.embed(anyString())).willReturn(Optional.empty());

        assertThat(backfill.backfill()).isZero();
        // 三次失败后退出（MAX_CONSECUTIVE_FAILURES=3），不会继续处理后面的条目
        verify(embeddingService, org.mockito.Mockito.times(3)).embed(anyString());
    }

    @Test
    @DisplayName("backfill(): 写入失败导致零进展时退出 —— 否则会对同一批无限重试")
    void shouldExitWhenNoProgress() {
        given(store.findPending(2)).willReturn(List.of(pending("维度不符的内容")));
        given(embeddingService.embed(anyString())).willReturn(Optional.of(new float[] {0.1f}));
        given(store.save(any(), any(), anyString())).willReturn(false);

        assertThat(backfill.backfill()).isZero();
    }
}
