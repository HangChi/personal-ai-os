package com.personalos.knowledge.splitter;

import static org.assertj.core.api.Assertions.assertThat;

import com.personalos.knowledge.reader.KnowledgeDocumentReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtectedMarkdownBlocksTest {
    @Test
    void oversizedStructuresStayWholeEvenWithOverlapAndShortPieceMerging() {
        List<String> structures = List.of(
                "```python\n    # 注释不是标题\n\n" + "    print('完整代码')  \n".repeat(180) + "```",
                "    # 缩进代码\n\n" + "    print('保留缩进')  \n".repeat(180),
                "| 名称 | 值 |\n| --- | --- |\n" + "| key | 完整表格内容 |\n".repeat(180),
                "名称\t值\n" + "key\t完整表格内容\n".repeat(180),
                "- 父项\n  - 子项\n\n" + "- 完整列表内容\n".repeat(180),
                "> 引用开始\n>\n" + "> 完整引用内容\n".repeat(180),
                "$$\n" + "x = a + b + c + d\n".repeat(180) + "$$",
                "`" + "inline_call_".repeat(180) + "`");
        for (String structure : structures) {
            String exact = structure.stripTrailing();
            String source = "# 文档\n\n前言。\n\n" + exact + "\n\n后记。";
            var chunks = new MarkdownStructureTextSplitter(1200, 350, 120).splitText(source);
            assertThat(chunks.stream().filter(chunk -> chunk.contains(exact)).count())
                    .as("结构必须完整落在一个块中：%s", exact.substring(0, 10)).isEqualTo(1);
            String joined = String.join("\n", chunks);
            assertThat(joined).contains("前言。", "后记。");
            // 重叠也不能把结构的一部分复制到别的块，出现次数必须守恒。
            assertThat(joined.split(java.util.regex.Pattern.quote(exact), -1).length - 1).isEqualTo(1);
        }
    }

    @Test
    void whitespaceInsideCodeIsNotNormalizedOrStripped() {
        String code = "    first = 1  \n\n    second = 2\t ";
        var chunks = new MarkdownStructureTextSplitter(12, 1, 8).splitText(code);
        assertThat(chunks).containsExactly(code);
    }

    @Test
    void utf8UploadKeepsProtectedCodeAndMetadataThroughReaderAndSplitter() {
        String code = "   ```python\r\n    # 原始注释\r\n\r\n    value = 1  \r\n   ```";
        var metadata = Map.<String, Object>of("filename", "code.md", "document_id", "synthetic");
        var documents = new KnowledgeDocumentReader(("\uFEFF" + code).getBytes(StandardCharsets.UTF_8), metadata).get();
        var chunks = new MarkdownStructureTextSplitter(12, 1, 8).apply(documents);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getText()).isEqualTo(code);
        assertThat(chunks.getFirst().getMetadata()).containsAllEntriesOf(metadata);
    }

    @Test
    void aLargePlainParagraphStillUsesTheExistingCharacterBudget() {
        String text = "普通正文。".repeat(1000);
        var chunks = new MarkdownStructureTextSplitter(1200, 1, 0).splitText(text);
        assertThat(chunks).hasSizeGreaterThan(1).allMatch(chunk -> chunk.length() <= 1200);
        assertThat(String.join("", chunks)).isEqualTo(text);
    }
}
