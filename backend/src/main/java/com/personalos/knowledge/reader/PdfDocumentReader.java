package com.personalos.knowledge.reader;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;

/** Extracts the text layer page by page and transcribes pages without readable text. */
public class PdfDocumentReader implements DocumentReader {
    private static final int MAX_PAGES = 500;
    private static final int MAX_VISION_PAGES = 20;
    private static final int MAX_RENDERED_PIXELS = 4_000_000;
    private static final int MAX_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final float RENDER_DPI = 144;

    private final byte[] bytes;
    private final Map<String, Object> metadata;
    private final VisionTextExtractor extractor;

    public PdfDocumentReader(byte[] bytes, Map<String, Object> metadata,
                             VisionTextExtractor extractor) {
        this.bytes = bytes;
        this.metadata = metadata;
        this.extractor = extractor;
    }

    @Override
    public List<Document> get() {
        List<String> pages = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(bytes, "", null, "",
                MemoryUsageSetting.setupMixed(16 * 1024 * 1024).streamCache)) {
            if (pdf.getNumberOfPages() > MAX_PAGES) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                        "PDF 页数超过上限（" + MAX_PAGES + " 页），请拆分后分批上传");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setPageEnd("\f");
            String[] extractedPages = stripper.getText(pdf).split("\f", -1);
            if (extractedPages.length < pdf.getNumberOfPages()) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                        "PDF 解析失败：无法确定页面顺序");
            }
            List<String> pageTexts = new ArrayList<>();
            List<Boolean> needsVision = new ArrayList<>();
            int visionPages = 0;
            for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                String text = ReaderSupport.normalize(extractedPages[index]);
                boolean scan = text.isEmpty() && hasContent(pdf.getPage(index));
                pageTexts.add(text);
                needsVision.add(scan);
                if (scan) {
                    if (++visionPages > MAX_VISION_PAGES) {
                        throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                                "PDF 需视觉识别的页面超过上限（" + MAX_VISION_PAGES + " 页），请拆分后上传");
                    }
                }
            }
            if (visionPages > 0 && extractor == null) {
                throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                        "当前未配置视觉模型，无法解析扫描版 PDF");
            }
            PDFRenderer renderer = visionPages > 0 ? new PDFRenderer(pdf) : null;
            if (renderer != null) {
                renderer.setSubsamplingAllowed(true);
            }
            for (int index = 0; index < pageTexts.size(); index++) {
                String text = pageTexts.get(index);
                if (needsVision.get(index)) {
                    text = ReaderSupport.normalize(extractor.extract(
                            renderPage(renderer, pdf.getPage(index), index), "image/jpeg"));
                }
                if (!text.isEmpty()) {
                    pages.add(text);
                }
            }
        } catch (InvalidPasswordException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 已加密，无法提取文本（请先去除密码后重新上传）");
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 解析失败：文件可能已损坏，或不是有效的 PDF");
        }
        return ReaderSupport.toDocuments(String.join("\n\n", pages), metadata);
    }

    private static boolean hasContent(PDPage page) throws IOException {
        try (InputStream content = page.getContents()) {
            return content.read() != -1;
        }
    }

    private static byte[] renderPage(PDFRenderer renderer, PDPage page, int index) throws IOException {
        double width = page.getCropBox().getWidth() * RENDER_DPI / 72;
        double height = page.getCropBox().getHeight() * RENDER_DPI / 72;
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0
                || width * height > MAX_RENDERED_PIXELS) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 第 " + (index + 1) + " 页尺寸过大，无法进行视觉识别");
        }
        BufferedImage image = renderer.renderImageWithDPI(index, RENDER_DPI, ImageType.RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpeg", output) || output.size() == 0
                || output.size() > MAX_IMAGE_BYTES) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "PDF 第 " + (index + 1) + " 页图像过大，无法进行视觉识别");
        }
        return output.toByteArray();
    }
}
