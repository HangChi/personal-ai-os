package com.personalos.knowledge;

import com.personalos.export.ExportContributor;
import com.personalos.export.ExportWriter;
import tools.jackson.core.JsonGenerator;
import com.personalos.knowledge.service.KnowledgeDocumentService;
import com.personalos.knowledge.service.VectorStoreInspector;
import org.springframework.stereotype.Component;

/**
 * 知识库导出（键 {@code knowledgeDocuments}）。
 *
 * <p><b>这里有一个必须如实呈现的限制</b>：上传的原始文件**不落库**
 * （V4 迁移的设计，只保留分块后的片段）。所以导出的是「元数据 + 分块文本」，
 * 不含原始 PDF/docx/图片。分块文本足以重建索引与检索内容，
 * 但要拿回原始文件只能重新上传。
 *
 * <p>不导出向量：它是「分块文本 + 嵌入模型」的派生物，一条 1024 维向量会让文件膨胀几十倍，
 * 而且换嵌入模型后完全失效。真正不可再生的只有文本。
 */
@Component
public class KnowledgeExportContributor implements ExportContributor {

    private final ExportWriter writer;

    private final KnowledgeDocumentService documentService;
    private final VectorStoreInspector inspector;

    public KnowledgeExportContributor(KnowledgeDocumentService documentService,
                                      VectorStoreInspector inspector, ExportWriter writer) {
        this.writer = writer;
        this.documentService = documentService;
        this.inspector = inspector;
    }

    @Override
    public String key() {
        return "knowledgeDocuments";
    }

    @Override
    public long writeTo(JsonGenerator json) {
        return writer.array(json, page -> documentService.exportBatch(page,
                ExportWriter.BATCH_SIZE), (out, document) -> {
            out.writeStartObject();
            out.writePOJOProperty("document", document);
            out.writeName("chunks");
            writer.array(out, page -> inspector.exportChunks(document.id(), page,
                    ExportWriter.BATCH_SIZE));
            out.writeEndObject();
        });
    }
}
