package com.personalos.knowledge.service;

import java.util.Locale;

/**
 * 知识文档「文件名 / 标题 / 类型」的规范化。
 *
 * <h2>为什么单独成类</h2>
 * 这几个函数全是纯映射，但每一个都对应过一条**静默错误**：
 * <ul>
 *   <li>不截断超长文件名 → 插入抛 {@code DataIntegrityViolation} → 用户看到「服务器内部错误」，
 *       而真实原因只是名字太长；截断时丢掉扩展名 → 换了错的解析器，报「解析失败」；</li>
 *   <li>图片的 MIME 写错 → 传给视觉模型的是一张「类型为 text/plain 的图片」，
 *       表现为「模型说什么都看不见」，日志里没有任何异常；</li>
 *   <li>不去掉路径成分 → 存进库里的文件名带 `../`，导出时拼出的路径跑到目录外。</li>
 * </ul>
 * 抽出来之后每条边界都能直接断言，不必起容器、传文件、看数据库。
 *
 * <p>列宽常量与 Flyway 迁移一一对应（{@code title} / {@code filename} 都是 300），
 * 改这里之前先看迁移。
 */
final class DocumentFilenameSupport {

    /** 与 {@code knowledge_document.title} 的 length 一致 */
    static final int TITLE_MAX_LENGTH = 300;

    /** 与 {@code knowledge_document.filename} 的 length 一致 */
    static final int FILENAME_MAX_LENGTH = 300;

    /** 扩展名长度上限：超过它就不认为是扩展名，避免把「v1.很长的一段」当成后缀 */
    private static final int MAX_EXTENSION_LENGTH = 10;

    private static final String FALLBACK_FILENAME = "untitled.txt";

    private DocumentFilenameSupport() {
    }

    /**
     * 规范化上传的文件名：去路径、限长、保扩展名。
     *
     * @param originalFilename 客户端原样传来的名字（可能是完整路径、可能为空）
     */
    static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return FALLBACK_FILENAME;
        }
        // 去掉任何路径成分：上传方可能带上完整路径，我们只需要文件名
        String normalized = originalFilename.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (filename.isBlank()) {
            return FALLBACK_FILENAME;
        }
        // 超长不截断时插入抛 DataIntegrityViolation，用户看到的是「服务器内部错误」。
        // 截断必须保住扩展名：它决定后续用哪个解析器。
        if (filename.length() > FILENAME_MAX_LENGTH) {
            int dot = filename.lastIndexOf('.');
            String extension = dot > 0 && filename.length() - dot <= MAX_EXTENSION_LENGTH
                    ? filename.substring(dot)
                    : "";
            int keep = Math.max(1, FILENAME_MAX_LENGTH - extension.length());
            filename = filename.substring(0, keep) + extension;
        }
        return filename;
    }

    /** 按列宽截断标题（不尝试保语义，标题只是展示用） */
    static String truncateTitle(String title) {
        return title.length() <= TITLE_MAX_LENGTH ? title : title.substring(0, TITLE_MAX_LENGTH);
    }

    /**
     * 扩展名 → MIME 类型。
     *
     * <p>图片的 MIME 不只是展示用：它会被传给视觉模型的 {@code Media}，
     * 写错会让模型收到一个「类型是 text/plain 的图片」，表现为识别不出任何内容。
     */
    static String contentTypeOf(String extension) {
        return switch (extension) {
            case "md", "markdown" -> "text/markdown";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "text/plain";
        };
    }

    /** 取小写扩展名；没有点号时返回空串 */
    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 去掉扩展名作为标题。
     *
     * <p>`.hidden` 这种以点开头的名字整体当作标题（`dot <= 0` 的判断），
     * 否则会得到一个空字符串，落库后列表里是一行空白。
     */
    static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        String base = dot <= 0 ? filename : filename.substring(0, dot);
        return base.isBlank() ? filename : base;
    }
}
