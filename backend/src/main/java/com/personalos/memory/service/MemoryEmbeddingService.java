package com.personalos.memory.service;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 记忆内容的向量化。
 *
 * <h2>「可选能力」是这里的第一原则</h2>
 * 向量只服务于排序与近重复判定，**不是记忆能否工作的前提**。因此所有失败
 * （模型未配置、端点超时、维度不符、返回空）都返回 {@link Optional#empty()}，
 * 由调用方降级到字符二元组或直接跳过判断 —— 绝不向上抛异常。
 * 反例的代价很具体：嵌模型挂了，用户连「记住这句话」都做不到。
 *
 * <h2>为什么查询向量必须带超时</h2>
 * 排序发生在**每轮对话的提示词装配路径**上。嵌入端点慢起来是几秒级，
 * 而它排在模型调用之前 —— 不设上限就是把「嵌入慢」放大成「聊天卡」。
 * 写入路径不设超时（那是用户主动触发的保存，值得等），但也走同一套异常兜底。
 */
@Service
public class MemoryEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEmbeddingService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModels;

    /**
     * 当前 embedding 模型名。
     *
     * <p>取自配置而不是从 {@link EmbeddingModel} 反查：框架没有「我是哪个模型」的 API。
     * 两个 provider 的配置项只会在各自启用时非空，所以「先 openai 后 ollama」的取值顺序
     * 等价于「谁生效算谁的」。
     *
     * <p>它同时是向量比较的命名空间（{@code memory.embedding_model}）。
     * 换模型后旧向量靠它被识别并跳过比较 —— 这一步不做，两个空间的余弦值
     * 会给出看似合理的错误结论。
     */
    private final String modelTag;

    public MemoryEmbeddingService(ObjectProvider<EmbeddingModel> embeddingModels,
                                  @Value("${spring.ai.openai.embedding.model:}") String openaiEmbeddingModel,
                                  @Value("${spring.ai.ollama.embedding.model:}") String ollamaEmbeddingModel) {
        this.embeddingModels = embeddingModels;
        this.modelTag = firstNonBlank(openaiEmbeddingModel, ollamaEmbeddingModel, "unknown");
    }

    /** 是否有可用的嵌入模型 */
    public boolean isAvailable() {
        return embeddingModels.getIfAvailable() != null;
    }

    /** 当前 embedding 模型标识（写入 {@code memory.embedding_model}） */
    public String modelTag() {
        return modelTag;
    }

    /**
     * 同步计算向量，不限时。
     *
     * @param text 文本
     * @return 向量；任何失败都是空
     */
    public Optional<float[]> embed(String text) {
        return embed(text, null);
    }

    /**
     * 计算向量，可限时。
     *
     * @param text    文本
     * @param timeout 等待上限；{@code null} 表示不限时（写入路径用）
     * @return 向量；失败、超时、维度异常都是空
     */
    public Optional<float[]> embed(String text, Duration timeout) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        EmbeddingModel model = embeddingModels.getIfAvailable();
        if (model == null) {
            return Optional.empty();
        }
        if (timeout == null) {
            try {
                return toVector(model.embed(text));
            } catch (RuntimeException ex) {
                log.debug("记忆向量化失败（已降级为无向量）：{}", ex.toString());
                return Optional.empty();
            }
        }

        // 与 ChatSummaryService 同一套路：模型调用没有单次超时，只能在虚拟线程里带超时等。
        // 被放弃的那次调用会在后台自然结束 —— 比钉住一个平台线程划算。
        FutureTask<float[]> task = new FutureTask<>(() -> toVector(model.embed(text)).orElse(null));
        Thread.ofVirtual().name("memory-embed").start(task);
        try {
            float[] vector = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return Optional.ofNullable(vector);
        } catch (TimeoutException ex) {
            task.cancel(true);
            log.debug("记忆向量化超时（{}），本轮降级为字符重叠排序", timeout);
            return Optional.empty();
        } catch (ExecutionException ex) {
            log.debug("记忆向量化失败（已降级）：{}", String.valueOf(ex.getCause()));
            return Optional.empty();
        } catch (InterruptedException ex) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * 把嵌入结果转成 float[]，并挡掉空结果。
     *
     * <p>零长度向量在 pgvector 上会直接报语法错误，且它不可能代表任何内容 ——
     * 与「没有向量」是同一件事，统一按空处理。
     */
    private Optional<float[]> toVector(float[] raw) {
        if (raw == null || raw.length == 0) {
            return Optional.empty();
        }
        return Optional.of(raw);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }
}
