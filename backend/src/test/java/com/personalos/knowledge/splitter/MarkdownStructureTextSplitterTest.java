package com.personalos.knowledge.splitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 结构感知切分器（2026-10-25 替换固定 token 切分）。
 *
 * <p>这些断言针对的是「换切分策略」要解决的三个具体问题：
 * 句子不被劈开、块带章节上下文、短块不成为噪声。
 */
class MarkdownStructureTextSplitterTest {

    private static MarkdownStructureTextSplitter splitter(int target, int min, int overlap) {
        return new MarkdownStructureTextSplitter(target, min, overlap);
    }

    private static List<String> split(MarkdownStructureTextSplitter splitter, String text) {
        return splitter.splitText(text);
    }

    @Test
    @DisplayName("按标题分节：每块带完整章节路径")
    void shouldAttachHeadingPath() {
        String markdown = """
                # 顶层

                开头的引言部分，内容足够长以单独成块，这里再补一些字凑长度。

                ## 第一节

                第一节的正文，同样足够长，不会被合并到别处去，再补一点字。

                ### 子节

                子节正文，也足够长，用来验证章节路径包含全部层级，再补一点字。
                """;

        List<String> blocks = split(splitter(200, 20, 0), markdown);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).startsWith("【章节：顶层】");
        assertThat(blocks.get(1)).startsWith("【章节：顶层 > 第一节】");
        assertThat(blocks.get(2)).startsWith("【章节：顶层 > 第一节 > 子节】");
    }

    @Test
    @DisplayName("兄弟标题不会串成路径（更深层级的旧标题必须被清掉）")
    void siblingHeadingsShouldNotLeakIntoPath() {
        String markdown = """
                # 书

                ## 第二章
                第二章正文，够长够长够长够长够长够长够长够长够长够长够长够长够长够长。

                ### 2.1 小节
                小节正文，够长够长够长够长够长够长够长够长够长够长够长够长够长够长够长。

                ## 第三章
                第三章正文，够长够长够长够长够长够长够长够长够长够长够长够长够长够长。
                """;

        List<String> blocks = split(splitter(200, 20, 0), markdown);

        String thirdChapter = blocks.stream()
                .filter(block -> block.contains("第三章正文"))
                .findFirst().orElseThrow();
        // 若三级标题没被清理，这里会错误地带上「2.1 小节」
        assertThat(thirdChapter).startsWith("【章节：书 > 第三章】");
        assertThat(thirdChapter).doesNotContain("2.1 小节");
    }

    @Test
    @DisplayName("超长节按段落切，不把句子从中间劈开")
    void shouldSplitByParagraphNotMidSentence() {
        String paragraphOne = "第一段。" + "内容".repeat(30);
        String paragraphTwo = "第二段。" + "内容".repeat(30);
        String markdown = "# 长节\n\n" + paragraphOne + "\n\n" + paragraphTwo + "\n";

        List<String> blocks = split(splitter(50, 10, 0), markdown);

        assertThat(blocks).hasSizeGreaterThan(1);
        // 每块要么以段落开头，要么是硬切的结果 —— 但绝不能把「第一段。」和它对半切开
        assertThat(blocks).anySatisfy(block -> assertThat(block).contains("第一段。"));
        assertThat(blocks).anySatisfy(block -> assertThat(block).contains("第二段。"));
    }

    @Test
    @DisplayName("短块并入前一块，且合并后只有**一个**章节前缀")
    void mergedBlocksShouldHaveSinglePrefix() {
        String markdown = """
                # 文档

                ## 长节
                这是一段足够长的正文，用来占据一个独立的块，再补一些字凑到目标长度以上。

                ## 短节
                很短。

                ## 另一个短节
                也短。
                """;

        List<String> blocks = split(splitter(120, 60, 0), markdown);

        // 关键断言：没有任何块出现两个前缀。
        // 2026-10-25 实测踩到过：先加前缀再合并，会让一块里叠进多个「【章节：…】」，
        // 既白占 token 又让嵌入混入互不相关的章节名。
        for (String block : blocks) {
            assertThat(block.split("【章节：", -1).length - 1)
                    .as("块内前缀数应恰好为 1：%s", block)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("没有标题的纯文本（PDF/docx 提取结果）也能切，且不加前缀")
    void plainTextWithoutHeadings() {
        String text = "第一段内容。" + "字".repeat(40) + "\n\n第二段内容。" + "字".repeat(40);

        List<String> blocks = split(splitter(60, 10, 0), text);

        assertThat(blocks).hasSizeGreaterThan(1);
        assertThat(blocks).allSatisfy(block -> assertThat(block).doesNotContain("【章节："));
    }

    @Test
    @DisplayName("正文里的 # 号不算标题（只在行首且带空格才算）")
    void inlineHashIsNotHeading() {
        String text = "# 真标题\n\nC# 是一门语言，这里的 # 不是标题。" + "字".repeat(30);

        List<String> blocks = split(splitter(500, 10, 0), text);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0)).startsWith("【章节：真标题】");
        assertThat(blocks.get(0)).contains("C# 是一门语言");
    }

    @Test
    @DisplayName("开启重叠时，后一块开头带上前一块尾部")
    void overlapShouldPrefixPreviousTail() {
        String markdown = "# 标题\n\n" + "甲".repeat(30) + "\n\n" + "乙".repeat(30);

        List<String> blocks = split(splitter(40, 5, 10), markdown);

        assertThat(blocks).hasSizeGreaterThan(1);
        assertThat(blocks.get(1)).contains("…");
    }

    @Test
    @DisplayName("空文本返回空列表（不产生空块）")
    void emptyInputProducesNothing() {
        assertThat(split(splitter(100, 10, 0), "")).isEmpty();
        assertThat(split(splitter(100, 10, 0), "   \n\n  ")).isEmpty();
    }

    @Test
    @DisplayName("切分不能丢内容：所有正文片段都能在产出块中找到")
    void shouldNotLoseContent() {
        // 构造一个「短节紧跟在长节后面」的文档 —— 这正是 2026-10-25 丢块缺陷的触发条件：
        // 合并前先 remove 再判断体积上限，超限时前一块既不回列表也没被加入，静默丢失。
        StringBuilder markdown = new StringBuilder("# 文档\n\n## 长节\n\n");
        markdown.append("甲".repeat(900)).append("\n\n");
        for (int index = 0; index < 6; index++) {
            markdown.append("## 短节").append(index).append("\n\n");
            markdown.append("乙".repeat(100)).append("\n\n");
        }

        String source = markdown.toString();
        List<String> blocks = split(splitter(600, 200, 0), source);
        String joined = String.join("", blocks);

        // 每个「标记字符」的出现次数必须守恒：丢块时会有大段甲/乙消失
        assertThat(countOf(joined, '甲'))
                .as("900 个「甲」应全部保留")
                .isEqualTo(countOf(source, '甲'));
        assertThat(countOf(joined, '乙'))
                .as("600 个「乙」应全部保留")
                .isEqualTo(countOf(source, '乙'));
        // 每个短节的标题都应在某个块里
        for (int index = 0; index < 6; index++) {
            String heading = "短节" + index;
            assertThat(joined).as("短节标题 %s 不应丢失", heading).contains(heading);
        }
    }

    @Test
    @DisplayName("真实规模的 Markdown：产出总字符不显著少于原文")
    void realWorldDocumentKeepsNearlyAllText() {
        StringBuilder markdown = new StringBuilder("# 标题\n\n");
        for (int section = 0; section < 8; section++) {
            markdown.append("## 第 ").append(section).append(" 节\n\n");
            for (int paragraph = 0; paragraph < 4; paragraph++) {
                markdown.append("这是第 ").append(section).append(" 节第 ").append(paragraph)
                        .append(" 段。").append("正文内容".repeat(20)).append("\n\n");
            }
            markdown.append("#### 小结").append(section).append("\n\n");
            markdown.append("小结一句话。\n\n");
        }
        String source = markdown.toString();

        List<String> blocks = split(splitter(1200, 350, 0), source);
        int produced = blocks.stream().mapToInt(String::length).sum();

        // 前缀会让总数变多，所以只需断言「不少于原文的 95%」——
        // 真正的下限意义在于：一旦丢块，缺口会是几十个百分点。
        assertThat(produced)
                .as("产出 %d 字符，原文 %d 字符", produced, source.length())
                .isGreaterThanOrEqualTo((int) (source.length() * 0.95));
    }

    private static int countOf(String text, char target) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == target) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- 代码围栏

    @Test
    @DisplayName("代码块里的 # 注释不算标题：不应制造出新的章节路径")
    void hashInsideFenceIsNotHeading() {
        String markdown = """
                # 部署说明

                下面是启动脚本，注意注释里也有井号。

                ```bash
                # 这一行是 shell 注释，不是标题
                export PORT=8080
                ## 这一行更不是二级标题
                java -jar app.jar
                ```

                脚本执行完就结束了，这里再补一些正文让这一节足够长以单独成块。
                """;

        List<String> blocks = split(splitter(1200, 50, 0), markdown);
        String joined = String.join("\n", blocks);

        // 关键断言：代码块内的注释文本不得出现在章节路径里
        assertThat(blocks)
                .as("代码块内的 # 注释被当成了标题，块内容=%s", blocks)
                .noneMatch(block -> block.contains("【章节：部署说明 > 这一行是 shell 注释，不是标题】"));
        assertThat(joined).as("代码块的正文内容不应丢失").contains("export PORT=8080");
        assertThat(joined).as("代码块的正文内容不应丢失").contains("java -jar app.jar");
    }

    @Test
    @DisplayName("代码块跨多个 # 行仍整体留在同一节内")
    void fenceKeepsFollowingHeadingIntact() {
        String markdown = """
                # 一

                ```text
                # A
                # B
                # C
                ```

                ## 二

                第二节的正文，长度足够单独成块，这里多补一些字凑够最小长度要求。
                """;

        List<String> blocks = split(splitter(1200, 20, 0), markdown);

        // 围栏内的 A/B/C 不该各成一节；真实的两个标题（一、二）应各出现一次
        assertThat(blocks).anyMatch(block -> block.contains("【章节：一 > 二】"));
        assertThat(blocks).noneMatch(block -> block.contains("【章节：一 > A】"));
    }

    @Test
    @DisplayName("波浪号围栏同样被识别")
    void tildeFenceIsRecognized() {
        String markdown = """
                # 标题

                ~~~python
                # 这是 python 注释
                print(1)
                ~~~

                正文补足长度，让这一节的内容足够单独成块而不被合并掉。
                """;

        List<String> blocks = split(splitter(1200, 20, 0), markdown);
        assertThat(blocks).noneMatch(block -> block.contains("【章节：标题 > 这是 python 注释】"));
        assertThat(String.join("\n", blocks)).contains("print(1)");
    }

    @Test
    @DisplayName("围栏闭合后的标题重新生效")
    void headingAfterFenceStillWorks() {
        String markdown = """
                # 开头

                ```
                code line
                ```

                ## 围栏之后

                这一段是围栏闭合后的真实章节，应当带自己的路径，内容补足长度。
                """;

        List<String> blocks = split(splitter(1200, 20, 0), markdown);
        assertThat(blocks)
                .as("围栏闭合后的标题必须重新被识别，块=%s", blocks)
                .anyMatch(block -> block.contains("【章节：开头 > 围栏之后】"));
    }
}
