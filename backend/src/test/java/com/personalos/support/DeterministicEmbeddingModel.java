package com.personalos.support;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 确定性的「词袋」嵌入模型 —— 仅供测试。
 *
 * <h2>为什么不用随机哈希向量</h2>
 * 随机向量的余弦相似度没有语义：检索结果近乎随机，于是「第一条命中来自正确文档」
 * 这类断言就失去了意义（只能验证"没崩"，不能验证"找对了"）。
 *
 * <h2>为什么是词袋</h2>
 * 词袋让**词面重叠**直接体现为相似度：两个片段共有的词越多，余弦相似度越高。
 * 这样「同主题片段排在前面」就是可断言的，而且完全确定 ——
 * 同样的输入永远得到同样的向量，测试可以重复执行。
 *
 * <h2>为什么只替换模型、不替换向量库</h2>
 * 测试仍然使用**真实**的 {@code PgVectorStore} 与真实的 pgvector 表。
 * 因此「维度一致性」「删除后零残留」这些断言验证的是真实链路，而不是替身的行为。
 *
 * <h2>它的局限（必须明确）</h2>
 * 没有语义泛化能力：同义词、改写过的问法检索不到。
 * 所以它只能验证**链路正确**，不能验证**检索效果**。效果验证需要真实嵌入模型（人工项）。
 */
public class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimensions;

    public DeterministicEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int index = 0; index < instructions.size(); index++) {
            embeddings.add(new Embedding(vectorOf(instructions.get(index)), index));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return vectorOf(document.getText());
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] vectorOf(String text) {
        float[] vector = new float[dimensions];
        boolean hasToken = false;
        if (text != null) {
            for (String token : tokenize(text)) {
                vector[Math.floorMod(token.hashCode(), dimensions)] += 1f;
                hasToken = true;
            }
        }

        if (!hasToken) {
            // 全零向量的余弦距离没有定义，给一个固定方向兜底，
            // 保证「空文本」也能被写入而不会导致 SQL 或索引异常
            vector[0] = 1f;
            return vector;
        }

        double squaredNorm = 0d;
        for (float value : vector) {
            squaredNorm += (double) value * value;
        }
        float scale = (float) (1d / Math.sqrt(squaredNorm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= scale;
        }
        return vector;
    }

    /**
     * 切词：ASCII 连续字母数字作为一个词，CJK 按单字切分。
     *
     * <p>CJK 不切词而是按单字，是为了不引入分词器依赖；对「词面重叠」这个目的来说，
     * 单字已经能提供足够信号。
     */
    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character < 128 && Character.isLetterOrDigit(character)) {
                ascii.append(Character.toLowerCase(character));
                continue;
            }
            if (ascii.length() > 0) {
                tokens.add(ascii.toString());
                ascii.setLength(0);
            }
            if (Character.isLetter(character)) {
                tokens.add(String.valueOf(character));
            }
        }
        if (ascii.length() > 0) {
            tokens.add(ascii.toString());
        }
        return tokens;
    }
}
