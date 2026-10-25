package com.personalos.knowledge.reader;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

/**
 * PDF 解析器：只取**文本层**。
 *
 * <h2>为什么不做 OCR</h2>
 * 扫描版 PDF 的每一页都是一张图，取文本层会得到空串。此时正确行为是**明确告诉用户**
 * 「这可能是扫描件，需要 OCR，当前未支持」，而不是引入 Tesseract（系统级依赖 + 中文语言包，
 * 与自托管单机「开箱即用」冲突，见 research R-01 / spec Assumptions）。
 *
 * <h2>为什么加密单独区分</h2>
 * 「PDF 已加密」和「PDF 损坏」对用户是两条完全不同的出路（前者去掉密码，后者重新导出）。
 * 归一成一句「解析失败」等于让用户去猜。
 */
public class PdfDocumentReader implements DocumentReader {

    private final byte[] bytes;
    private final Map<String, Object> metadata;

    public PdfDocumentReader(byte[] bytes, Map<String, Object> metadata) {
        this.bytes = bytes;
        this.metadata = metadata;
    }

    @Override
    public List<Document> get() {
        String text;
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            text = new PDFTextStripper().getText(pdf);
        } catch (InvalidPasswordException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 已加密，无法提取文本（请先去除密码后重新上传）");
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 解析失败：文件可能已损坏，或不是有效的 PDF");
        }
        return ReaderSupport.toDocuments(text, metadata);
    }
}
