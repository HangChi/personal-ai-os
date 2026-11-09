package com.personalos.knowledge.splitter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证结构边界及原文范围，防止切块把相邻正文吞入代码、公式或列表。 */
class MarkdownBlockScannerBoundaryTest {
    private static List<String> protectedBlocks(String text) {
        return MarkdownBlockScanner.blocks(text).stream()
                .map(span -> text.substring(span.start(), span.end())).toList();
    }

    @Test
    void fenceRequiresMatchingMarkerIndentationAndAnOtherwiseEmptyClosingLine() {
        String code = "~~~lang\n    ~~~\n```\n~~~ trailing text\n~~~~";
        assertThat(protectedBlocks(code + "\nplain text")).containsExactly(code);
        assertThat(protectedBlocks("```bad`info\nplain text")).isEmpty();
        assertThat(protectedBlocks("```\nnot closed")).containsExactly("```\nnot closed");
    }

    @Test
    void indentedCodePreservesTabsAndBlankLinesButStopsBeforeOrdinaryText() {
        String code = "\tfirst\n\n    second";
        assertThat(protectedBlocks("# title\n" + code + "\n\nnormal"))
                .containsExactly(code);
        assertThat(protectedBlocks("    code\n\n")).containsExactly("    code");
        assertThat(protectedBlocks("paragraph\n    continuation")).isEmpty();
    }

    @Test
    void formulasSupportBothDelimitersAndKeepUnclosedInputWhole() {
        assertThat(protectedBlocks("$$x$$\nplain\n\\[y\\]\nplain"))
                .containsExactly("$$x$$", "\\[y\\]");
        assertThat(protectedBlocks("\\[\nx + y\n\\]\nplain"))
                .containsExactly("\\[\nx + y\n\\]");
        assertThat(protectedBlocks("$$\nx + y")).containsExactly("$$\nx + y");
    }

    @Test
    void tablesStopAtBlankOrNonTableRowsAndSingleTabLinesRemainOrdinaryText() {
        String table = "a | b\n--- | ---\n1 | 2";
        assertThat(protectedBlocks(table + "\nplain")).containsExactly(table);
        assertThat(protectedBlocks(table + "\n\nplain")).containsExactly(table);
        assertThat(protectedBlocks(table)).containsExactly(table);
        assertThat(protectedBlocks("a\tb\n1\t2\nplain"))
                .containsExactly("a\tb\n1\t2");
        assertThat(protectedBlocks("a\tb\nplain")).isEmpty();
        assertThat(protectedBlocks("a\tb")).isEmpty();
    }

    @Test
    void quotesAllowLazyContinuationButEndAtNewBlocksOrSeparatedParagraphs() {
        assertThat(protectedBlocks("> quote\nlazy continuation\n# heading"))
                .containsExactly("> quote\nlazy continuation");
        assertThat(protectedBlocks("> quote\n\nplain paragraph"))
                .containsExactly("> quote");
        assertThat(protectedBlocks("> quote\n\n")).containsExactly("> quote");
        assertThat(protectedBlocks("> quote\n- item"))
                .containsExactly("> quote", "- item");
    }

    @Test
    void listsStopBeforeEveryIndependentBlockAndKeepIndentedAndLazyContinuation() {
        String list = "1. first\n  continuation\nlazy continuation\n2. second";
        assertThat(protectedBlocks(list + "\n\nplain")).containsExactly(list);
        assertThat(protectedBlocks("- item\n\n")).containsExactly("- item");
        for (String next : List.of("# heading", "> quote", "$$x$$", "\\[x\\]",
                "```\ncode\n```", "column | value", "column\tvalue")) {
            assertThat(protectedBlocks("- item\n" + next).getFirst())
                    .as("独立块不属于前一个列表：%s", next).isEqualTo("- item");
        }
    }

    @Test
    void inlineCodeHonorsEscapingAndDelimiterLengthWithoutInventingClosingMarkers() {
        assertThat(MarkdownBlockScanner.inlineCode("\\`escaped` unclosed ``"))
                .isEmpty();
        String text = "\\\\`actual` and ``a ` b`` tail `";
        assertThat(MarkdownBlockScanner.inlineCode(text).stream()
                .map(span -> text.substring(span.start(), span.end())).toList())
                .containsExactly("`actual`", "``a ` b``");
    }

    @Test
    void headingProtectionKeepsInlineCodeOnBothSidesOfABlock() {
        String text = "before `# inline`\n\n```\n# code\n```\n\nafter `# tail`";
        assertThat(MarkdownBlockScanner.headingProtection(text).stream()
                .map(span -> text.substring(span.start(), span.end())).toList())
                .containsExactly("`# inline`", "```\n# code\n```", "`# tail`");
        assertThat(MarkdownBlockScanner.trimOutsideBlocks("  plain  ", List.of()))
                .isEqualTo("plain");
        assertThat(MarkdownBlockScanner.trimOutsideBlocks("   ", List.of())).isEmpty();
    }
}
