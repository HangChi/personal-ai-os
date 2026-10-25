package com.personalos.knowledge.reader;

/**
 * 「图片 → 文本」的能力。
 *
 * <p>抽成接口的唯一目的是让 {@link ImageDocumentReader} 可以被单测覆盖：
 * 视觉模型无法在自动化测试里稳定复现（需要真实多模态模型与网络），
 * 而解析器的分支逻辑（空结果、异常、元数据附加）与模型能力无关，应当独立验证。
 */
public interface VisionTextExtractor {

    /**
     * @param image    图片字节
     * @param mimeType 图片 MIME 类型（如 {@code image/png}）
     * @return 图片中的文字；图片没有文字时返回空串
     */
    String extract(byte[] image, String mimeType);
}
