package com.personalos.knowledge.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personalos.common.exception.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/**
 * 图片解析器单测：视觉能力用假实现替换。
 *
 * <p>真实视觉模型无法在自动化测试里稳定复现（要网络、要支持多模态的模型），
 * 但解析器本身的分支 —— 拿文本、归一、附加元数据、空结果报错、MIME 透传 ——
 * 与模型能力无关，必须被覆盖。
 */
class ImageDocumentReaderTest {

    private static final Map<String, Object> METADATA = Map.of("document_id", "doc-3");
    private static final byte[] FAKE_PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G'};

    @Test
    @DisplayName("模型返回文本 → 进入索引，并把图片 MIME 透传给提取器")
    void extractsTextViaVisionModel() {
        AtomicReference<String> seenMime = new AtomicReference<>();
        VisionTextExtractor extractor = (image, mimeType) -> {
            seenMime.set(mimeType);
            assertThat(image).isEqualTo(FAKE_PNG);
            return "板书内容：光合作用需要光照";
        };

        List<Document> documents = new ImageDocumentReader(FAKE_PNG, "image/png", METADATA, extractor).get();

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getText()).isEqualTo("板书内容：光合作用需要光照");
        assertThat(documents.get(0).getMetadata()).containsEntry("document_id", "doc-3");
        assertThat(seenMime.get()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("模型认为图里没有文字（空串）→ 报「未提取到文本」，不入库空文档")
    void emptyVisionResultIsRejected() {
        VisionTextExtractor extractor = (image, mimeType) -> "";

        assertThatThrownBy(() -> new ImageDocumentReader(FAKE_PNG, "image/png", METADATA, extractor).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");
    }

    @Test
    @DisplayName("模型返回空白（含换行）→ 同样按空结果处理")
    void blankVisionResultIsRejected() {
        VisionTextExtractor extractor = (image, mimeType) -> "   \n\t  ";

        assertThatThrownBy(() -> new ImageDocumentReader(FAKE_PNG, "image/png", METADATA, extractor).get())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未提取到文本");
    }
}
