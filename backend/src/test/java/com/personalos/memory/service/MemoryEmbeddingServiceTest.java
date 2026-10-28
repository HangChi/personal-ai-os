package com.personalos.memory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link MemoryEmbeddingService} 单元测试。
 *
 * <p>核心不变量：**向量化失败必须是「降级」而不是「报错」**。
 * 记忆本身是约束性事实，嵌入只是排序增强 —— 嵌入端点挂了，用户仍然应该能记住东西。
 */
class MemoryEmbeddingServiceTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<EmbeddingModel> providerOf(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(model);
        return provider;
    }

    @Test
    @DisplayName("modelTag(): 优先取 openai 的配置，其次 ollama（两个 provider 只会有一个非空）")
    void modelTagShouldPreferOpenai() {
        MemoryEmbeddingService service = new MemoryEmbeddingService(
                providerOf(null), "qwen-embedding", "bge-m3");

        assertThat(service.modelTag()).isEqualTo("qwen-embedding");
    }

    @Test
    @DisplayName("modelTag(): 都没配时给 unknown，而不是空串（空串会让 SQL 的模型过滤失效）")
    void modelTagShouldFallBackToUnknown() {
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(null), "", "  ");

        assertThat(service.modelTag()).isEqualTo("unknown");
    }

    @Test
    @DisplayName("isAvailable(): 没有嵌入模型时为 false，回填任务据此跳过")
    void shouldReportUnavailable() {
        assertThat(new MemoryEmbeddingService(providerOf(null), "m", "").isAvailable()).isFalse();
    }

    @Test
    @DisplayName("embed(): 空文本不调用模型（省一次网络往返）")
    void shouldSkipBlankText() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        assertThat(service.embed("   ")).isEmpty();
        assertThat(service.embed((String) null)).isEmpty();
    }

    @Test
    @DisplayName("embed(): 模型抛异常时返回空，不向上抛")
    void shouldSwallowModelError() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        given(model.embed(anyString())).willThrow(new IllegalStateException("connection refused"));
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        assertThat(service.embed("周报周五交")).isEmpty();
    }

    @Test
    @DisplayName("embed(): 零长度向量按「没有向量」处理（pgvector 对空向量的报错没有可读信息）")
    void shouldTreatEmptyVectorAsAbsent() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        given(model.embed(anyString())).willReturn(new float[0]);
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        assertThat(service.embed("周报周五交")).isEmpty();
    }

    @Test
    @DisplayName("embed(): 正常返回向量")
    void shouldReturnVector() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        given(model.embed(anyString())).willReturn(new float[] {0.1f, 0.2f, 0.3f});
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        assertThat(service.embed("周报周五交")).hasValueSatisfying(vector ->
                assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f));
    }

    @Test
    @DisplayName("embed(timeout): 带超时时正常返回（查询向量走这条路）")
    void shouldReturnVectorWithinTimeout() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        given(model.embed(anyString())).willReturn(new float[] {0.5f});
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        assertThat(service.embed("会议安排", Duration.ofSeconds(2))).isPresent();
    }

    @Test
    @DisplayName("embed(timeout): 超时后返回空 —— 它排在每轮对话的关键路径上，不能无限等")
    void shouldGiveUpOnTimeout() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        given(model.embed(anyString())).willAnswer(invocation -> {
            Thread.sleep(500);
            return new float[] {0.5f};
        });
        MemoryEmbeddingService service = new MemoryEmbeddingService(providerOf(model), "m", "");

        long start = System.nanoTime();
        assertThat(service.embed("会议安排", Duration.ofMillis(50))).isEmpty();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).as("应在超时后立即返回，而不是等模型跑完").isLessThan(400);
    }
}
