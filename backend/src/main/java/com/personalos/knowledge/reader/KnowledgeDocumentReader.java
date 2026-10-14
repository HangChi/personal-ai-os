package com.personalos.knowledge.reader;

import com.personalos.common.api.ErrorCode;
import com.personalos.common.exception.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.core.io.ByteArrayResource;

/**
 * 知识文档读取器：把上传的字节解析成 {@link Document}，并附上来源元数据。
 *
 * <h2>为什么自己不写解析、而是用 TextReader</h2>
 * 文本读取本身没有业务价值，框架的 {@link TextReader} 已经处理了资源读取与字符集解码。
 * 本类只做三件框架不做的事：
 * <ol>
 *   <li><b>严格 UTF-8 校验</b>：先用 REPORT 模式试解码，避免 GBK 等编码被"尽力解码"成乱码后
 *       静默写入向量库 —— 乱码一旦入库，检索出来的就是一堆无意义字符，且很难追溯是哪一步坏的。</li>
 *   <li><b>去除 BOM</b>：带 BOM 的文件首个字符是 U+FEFF，会让第一个片段以不可见字符开头，
 *       影响关键词重合度计算与展示。</li>
 *   <li><b>附加来源元数据</b>：文档标识、标题、文件名、片段序号 —— 这是删除时清理向量的依据。</li>
 * </ol>
 */
public class KnowledgeDocumentReader implements DocumentReader {

    /** UTF-8 BOM */
    private static final String BOM = "\uFEFF";

    private final byte[] bytes;
    private final Map<String, Object> metadata;

    /**
     * @param bytes    原始文件字节
     * @param metadata 需要附加到每个片段上的元数据
     */
    public KnowledgeDocumentReader(byte[] bytes, Map<String, Object> metadata) {
        this.bytes = bytes;
        this.metadata = metadata;
    }

    @Override
    public List<Document> get() {
        requireValidUtf8();

        // 用框架的 TextReader 读取资源；字符集默认 UTF-8
        List<Document> parsed = new TextReader(new ByteArrayResource(bytes)).get();

        List<Document> documents = new ArrayList<>(parsed.size());
        for (Document document : parsed) {
            String text = stripBom(document.getText());
            if (text == null || text.isBlank()) {
                continue;
            }
            documents.add(new Document(text, metadata));
        }
        return documents;
    }

    /**
     * 严格校验字节是否为合法 UTF-8。
     *
     * <p>刻意不用「先解码再看有没有替换字符」的做法：那是启发式的，会把真正的 U+FFFD 误判成编码错误。
     */
    private void requireValidUtf8() {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException ex) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT,
                    "文件不是有效的 UTF-8 编码，请转换编码后重新上传（当前仅支持 UTF-8）");
        }
    }

    private String stripBom(String text) {
        if (text == null) {
            return null;
        }
        return text.startsWith(BOM) ? text.substring(BOM.length()) : text;
    }
}
