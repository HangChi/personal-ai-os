package com.personalos.knowledge.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 文件名/标题/类型规范化的边界。
 *
 * <p>这些函数原先散在 {@code KnowledgeDocumentService} 的私有方法里，
 * 只能靠「上传一个文件、看数据库里存了什么」间接验证。
 * 而它们每一个都对应过一条静默错误（超长文件名 500、截断丢扩展名导致换错解析器、
 * 图片 MIME 写错导致模型读不出图）。
 */
class DocumentFilenameSupportTest {

    @Test
    @DisplayName("空名字退回 untitled.txt，而不是让插入炸在 NOT NULL 上")
    void handlesBlankNames() {
        assertThat(DocumentFilenameSupport.sanitizeFilename(null)).isEqualTo("untitled.txt");
        assertThat(DocumentFilenameSupport.sanitizeFilename("")).isEqualTo("untitled.txt");
        assertThat(DocumentFilenameSupport.sanitizeFilename("   ")).isEqualTo("untitled.txt");
    }

    @Test
    @DisplayName("去掉路径成分：反斜杠与正斜杠都要处理")
    void stripsPathComponents() {
        assertThat(DocumentFilenameSupport.sanitizeFilename("/tmp/upload/排课.md")).isEqualTo("排课.md");
        assertThat(DocumentFilenameSupport.sanitizeFilename("C:\\Users\\me\\课表.docx")).isEqualTo("课表.docx");
        // 只剩路径、没有文件名时同样退回默认值
        assertThat(DocumentFilenameSupport.sanitizeFilename("/tmp/")).isEqualTo("untitled.txt");
    }

    @Test
    @DisplayName("超长文件名按列宽截断，且必须保住扩展名（它决定用哪个解析器）")
    void truncatesButKeepsExtension() {
        String longName = "很长的名字".repeat(80) + ".pdf"; // 400 + 4 字符

        String result = DocumentFilenameSupport.sanitizeFilename(longName);

        assertThat(result.length()).isLessThanOrEqualTo(DocumentFilenameSupport.FILENAME_MAX_LENGTH);
        assertThat(result).as("扩展名丢了就会换错解析器，报「解析失败」").endsWith(".pdf");
    }

    @Test
    @DisplayName("超长且没有扩展名：正常截断，不额外加后缀")
    void truncatesWithoutExtension() {
        String result = DocumentFilenameSupport.sanitizeFilename("无后缀".repeat(200));

        assertThat(result.length()).isEqualTo(DocumentFilenameSupport.FILENAME_MAX_LENGTH);
        assertThat(result).doesNotContain(".");
    }

    @Test
    @DisplayName("过长的「扩展名」不被当作扩展名：不为保后缀而多削正文")
    void ignoresOverlongExtension() {
        // 点号后 21 个字符 > MAX_EXTENSION_LENGTH(10)，不算扩展名
        String name = "x".repeat(295) + "." + "y".repeat(20);

        String result = DocumentFilenameSupport.sanitizeFilename(name);

        assertThat(result).hasSize(DocumentFilenameSupport.FILENAME_MAX_LENGTH);
        assertThat(result)
                .as("当作扩展名保住的话，主体会被多削 21 个字符（只剩 279 个 x）")
                .startsWith("x".repeat(DocumentFilenameSupport.FILENAME_MAX_LENGTH - 5))
                .endsWith("yyyy");
    }

    @Test
    @DisplayName("标题按列宽截断，边界值本身不动")
    void truncatesTitle() {
        String exact = "标".repeat(DocumentFilenameSupport.TITLE_MAX_LENGTH);
        String over = "标".repeat(DocumentFilenameSupport.TITLE_MAX_LENGTH + 1);

        assertThat(DocumentFilenameSupport.truncateTitle(exact)).isEqualTo(exact);
        assertThat(DocumentFilenameSupport.truncateTitle(over)).hasSize(DocumentFilenameSupport.TITLE_MAX_LENGTH);
    }

    @Test
    @DisplayName("图片 MIME 必须精确：写错会让视觉模型收到「text/plain 的图片」而识别不出任何内容")
    void imageMimeTypesAreExact() {
        assertThat(DocumentFilenameSupport.contentTypeOf("png")).isEqualTo("image/png");
        assertThat(DocumentFilenameSupport.contentTypeOf("jpg")).isEqualTo("image/jpeg");
        assertThat(DocumentFilenameSupport.contentTypeOf("jpeg")).isEqualTo("image/jpeg");
        assertThat(DocumentFilenameSupport.contentTypeOf("webp")).isEqualTo("image/webp");
        assertThat(DocumentFilenameSupport.contentTypeOf("gif")).isEqualTo("image/gif");
    }

    @Test
    @DisplayName("文档 MIME：markdown / pdf / docx 各自正确，未知类型回落 text/plain")
    void documentMimeTypes() {
        assertThat(DocumentFilenameSupport.contentTypeOf("md")).isEqualTo("text/markdown");
        assertThat(DocumentFilenameSupport.contentTypeOf("markdown")).isEqualTo("text/markdown");
        assertThat(DocumentFilenameSupport.contentTypeOf("pdf")).isEqualTo("application/pdf");
        assertThat(DocumentFilenameSupport.contentTypeOf("docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(DocumentFilenameSupport.contentTypeOf("exe"))
                .as("未知类型回落 text/plain 由上传白名单另行拦截")
                .isEqualTo("text/plain");
    }

    @Test
    @DisplayName("扩展名一律小写；没有点号时为空串")
    void extractsExtension() {
        assertThat(DocumentFilenameSupport.extensionOf("A.PDF")).isEqualTo("pdf");
        assertThat(DocumentFilenameSupport.extensionOf("课表.docx")).isEqualTo("docx");
        assertThat(DocumentFilenameSupport.extensionOf("无后缀")).isEmpty();
    }

    @Test
    @DisplayName("去扩展名当标题：以点开头的隐藏文件整体保留，否则会得到空标题")
    void stripsExtensionForTitle() {
        assertThat(DocumentFilenameSupport.stripExtension("排课说明.md")).isEqualTo("排课说明");
        assertThat(DocumentFilenameSupport.stripExtension("无后缀")).isEqualTo("无后缀");
        assertThat(DocumentFilenameSupport.stripExtension(".gitignore"))
                .as("dot <= 0 视为没有扩展名，否则标题变成空串")
                .isEqualTo(".gitignore");
    }
}
