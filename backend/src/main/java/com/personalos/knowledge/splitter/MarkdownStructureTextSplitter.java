package com.personalos.knowledge.splitter;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * 结构感知的 Markdown 切分器（2026-10-25 替换原固定 token 切分）。
 *
 * <h2>为什么换掉 TokenTextSplitter</h2>
 * 固定 token 切分按 token 数硬切，完全不管文本结构：
 * <ul>
 *   <li>一句话从中间断开，两块都只剩半边语义，检索任一半都难以命中完整答案；</li>
 *   <li>切出的块不带任何「这段属于哪个章节」的信息，模型看到的是孤立片段；</li>
 *   <li>标题行、表格、代码块的边界都可能被切断。</li>
 * </ul>
 *
 * <h2>策略</h2>
 * <ol>
 *   <li>**按标题分节**：以 {@code # ~ ######} 为边界，每节记录完整标题路径
 *       （如 {@code 1. B-tree > 内部结构}）。没有标题的文本（PDF/docx 提取结果）
 *       自然退化为单节，后续按段落处理 —— 兼容行为不需要额外分支。</li>
 *   <li>**保护结构再按段落切**：代码、表格、列表、引用和多行公式不可拆开；普通
 *       段落累积到目标长度，超长普通段落才按字符兜底。</li>
 *   <li>**标题路径前缀**：每块前面加 {@code 【章节：…】}。这让检索能命中章节信息，
 *       也让模型看到片段在原文中的位置。</li>
 *   <li>**短块合并**：低于最小字符数的块并入相邻块，避免出现只有半句话的噪声块
 *       （评测中发现 29 / 92 字符的样例文档片段会挤进检索结果）。</li>
 * </ol>
 *
 * <h2>重叠</h2>
 * 支持可配置的字符重叠（默认 0）。结构切分已在自然边界断开，重叠的收益主要来自
 * 同节的上下文衔接；包含受保护结构的相邻块跳过重叠，防止复制半段结构。
 */
public class MarkdownStructureTextSplitter extends TextSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\n\\s*\n");

    /** 标题路径前缀的最大长度，避免前缀比正文还长 */
    private static final int MAX_PREFIX_LENGTH = 120;

    private final int targetChars;
    private final int minChunkChars;
    private final int overlapChars;

    public MarkdownStructureTextSplitter(int targetChars, int minChunkChars, int overlapChars) {
        this.targetChars = Math.max(targetChars, 1);
        this.minChunkChars = Math.max(minChunkChars, 1);
        this.overlapChars = Math.max(overlapChars, 0);
    }

    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<Section> sections = splitByHeadings(text);

        // 先产出**不带前缀**的原始片段并记下它所属的章节路径，合并完再加前缀。
        // 顺序很关键：若先加前缀再合并，一个合并块里会叠进多个「【章节：…】」——
        // 既白占 token，又让嵌入混入互不相关的章节名（2026-10-25 实测踩到）。
        List<Piece> pieces = new ArrayList<>();
        for (Section section : sections) {
            for (Block block : splitSection(section)) {
                if (!block.text().isBlank()) {
                    pieces.add(new Piece(section.headingPath(), block.text()));
                }
            }
        }

        List<Piece> merged = mergeSmallPieces(pieces);

        List<String> blocks = new ArrayList<>(merged.size());
        for (Piece piece : merged) {
            blocks.add(prefix(piece.headingPath()) + piece.text());
        }
        return blocks;
    }

    /** 一个待输出片段：章节路径 + 正文（正文不含前缀） */
    private record Piece(List<String> headingPath, String text) {
    }

    private record Block(String text, boolean protectedContent) {}
    private record Unit(String text, boolean protectedContent, int start, int end) {}

    // ------------------------------------------------------------------ 分节

    /** 一节：标题路径 + 正文 */
    private record Section(List<String> headingPath, String body) {
    }

    private List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        // 栈：下标为标题级别（1..6），值为该级别当前生效的标题文本
        String[] stack = new String[6];
        StringBuilder body = new StringBuilder();
        List<String> currentPath = List.of();
        List<MarkdownBlockScanner.Span> protectedSpans = MarkdownBlockScanner.headingProtection(text);
        int spanIndex = 0;
        int offset = 0;

        for (String line : text.split("\n", -1)) {
            while (spanIndex < protectedSpans.size() && offset >= protectedSpans.get(spanIndex).end()) spanIndex++;
            boolean protectedLine = spanIndex < protectedSpans.size()
                    && offset >= protectedSpans.get(spanIndex).start();
            offset += line.length() + 1;
            String newline = offset <= text.length() ? "\n" : "";
            Matcher matcher = protectedLine ? null : HEADING.matcher(line.strip());
            if (matcher != null && matcher.matches()) {
                // 新标题：先把上一节收尾
                if (body.toString().isBlank() == false || !currentPath.isEmpty()) {
                    sections.add(new Section(currentPath, body.toString()));
                }
                int level = matcher.group(1).length();
                stack[level - 1] = matcher.group(2);
                // 弹出更深层级，否则旧的小节标题会残留成路径的一部分
                for (int deeper = level; deeper < stack.length; deeper++) {
                    stack[deeper] = null;
                }
                currentPath = headingPath(stack);
                body.setLength(0);
                // 标题行本身也进正文：它是这段话最好的摘要
                body.append(line.strip()).append(newline);
                continue;
            }
            body.append(line).append(newline);
        }
        if (body.toString().isBlank() == false || !currentPath.isEmpty()) {
            sections.add(new Section(currentPath, body.toString()));
        }
        return sections;
    }

    private static List<String> headingPath(String[] stack) {
        List<String> path = new ArrayList<>();
        for (String title : stack) {
            if (title != null) {
                path.add(title);
            }
        }
        return path;
    }

    // ------------------------------------------------------------------ 切节

    private List<Block> splitSection(Section section) {
        String body = MarkdownBlockScanner.trimOutsideBlocks(section.body(), MarkdownBlockScanner.blocks(section.body()));
        if (body.isEmpty()) {
            return List.of();
        }
        List<MarkdownBlockScanner.Span> spans = MarkdownBlockScanner.blocks(body);
        if (body.length() <= targetChars) {
            return List.of(new Block(body, !spans.isEmpty() || !MarkdownBlockScanner.inlineCode(body).isEmpty()));
        }

        List<Unit> units = new ArrayList<>();
        int offset = 0;
        for (MarkdownBlockScanner.Span span : spans) {
            addParagraphs(units, body.substring(offset, span.start()), offset);
            units.add(new Unit(body.substring(span.start(), span.end()), true, span.start(), span.end()));
            offset = span.end();
        }
        addParagraphs(units, body.substring(offset), offset);

        List<Block> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean protectedContent = false;
        for (int index = 0; index < units.size(); index++) {
            Unit unit = units.get(index);
            String value = unit.text();
            if (value.length() > targetChars) {
                if (!current.isEmpty()) {
                    pieces.add(new Block(current.toString(), protectedContent));
                    current.setLength(0);
                    protectedContent = false;
                }
                // 原子结构可超过目标长度，不能为凑块大小破坏代码/表格。
                if (unit.protectedContent()) pieces.add(new Block(value, true));
                else pieces.addAll(hardSplit(value));
                continue;
            }
            // 拆出原子结构时不凭空在相邻行之间增加空行，保留原有分隔。
            String separator = index > 0 && (unit.protectedContent() || units.get(index - 1).protectedContent())
                    ? body.substring(units.get(index - 1).end(), unit.start()) : "\n\n";
            if (current.length() + value.length() + separator.length() > targetChars && !current.isEmpty()) {
                pieces.add(new Block(current.toString(), protectedContent));
                current.setLength(0);
                protectedContent = false;
            }
            if (!current.isEmpty()) {
                current.append(separator);
            }
            current.append(value);
            protectedContent |= unit.protectedContent() || !MarkdownBlockScanner.inlineCode(value).isEmpty();
        }
        if (!current.isEmpty()) {
            pieces.add(new Block(current.toString(), protectedContent));
        }

        return applyOverlap(pieces);
    }

    private void addParagraphs(List<Unit> units, String text, int offset) {
        // 行内代码中允许换行/空行，也先作为原子范围跳过段落分隔符。
        List<MarkdownBlockScanner.Span> inline = MarkdownBlockScanner.inlineCode(text);
        Matcher separators = PARAGRAPH_SEPARATOR.matcher(text);
        int start = 0;
        int span = 0;
        while (separators.find()) {
            while (span < inline.size() && inline.get(span).end() <= separators.start()) span++;
            if (span < inline.size() && inline.get(span).start() < separators.end()) continue;
            addParagraph(units, text.substring(start, separators.start()), offset + start);
            start = separators.end();
        }
        addParagraph(units, text.substring(start), offset + start);
    }

    private void addParagraph(List<Unit> units, String text, int offset) {
        String value = text.strip();
        if (!value.isEmpty()) {
            int start = offset + text.length() - text.stripLeading().length();
            units.add(new Unit(value, false, start, start + value.length()));
        }
    }

    /**
     * 兜底：单段超过目标长度时按**字符**切，并尽量在句末断开。
     *
     * <p>本切分器的目标长度是**字符**，
     * 而 token 切分器的 800 token 在中文上可能落到 3000 字符 —— 实测出现过
     * 3089 字符的块，远超 1200 的目标，且和「按字符控制块大小」的语义不一致。
     * 一个原则：既然对外承诺的是字符数，兜底也必须以字符为准。
     */
    private List<Block> hardSplit(String text) {
        List<Block> pieces = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > targetChars) {
            int cut = findBreakPoint(remaining);
            for (MarkdownBlockScanner.Span span : MarkdownBlockScanner.inlineCode(remaining)) {
                if (span.start() < cut && cut < span.end()) {
                    cut = span.start() > 0 ? span.start() : span.end();
                    break;
                }
            }
            if (cut < remaining.length() && Character.isHighSurrogate(remaining.charAt(cut - 1))
                    && Character.isLowSurrogate(remaining.charAt(cut))) cut++;
            String value = remaining.substring(0, cut).strip();
            pieces.add(new Block(value, !MarkdownBlockScanner.inlineCode(value).isEmpty()));
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty()) {
            pieces.add(new Block(remaining, !MarkdownBlockScanner.inlineCode(remaining).isEmpty()));
        }
        return pieces;
    }

    /**
     * 在目标长度附近找一个「尽量自然」的断点。
     *
     * <p>从目标位置往前找句末标点（。！？；换行），最多退让 30% 长度；
     * 找不到就退到空格，再找不到就硬切 —— 硬切是最后手段，因为它会把句子劈开。
     */
    private int findBreakPoint(String text) {
        int limit = Math.min(targetChars, text.length());
        int floor = (int) (limit * 0.7);
        for (int index = limit - 1; index >= floor; index--) {
            char ch = text.charAt(index);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == '\n' || ch == '.') {
                return index + 1;
            }
        }
        for (int index = limit - 1; index >= floor; index--) {
            if (Character.isWhitespace(text.charAt(index))) {
                return index + 1;
            }
        }
        return limit;
    }

    private String prefix(List<String> headingPath) {
        if (headingPath.isEmpty()) {
            return "";
        }
        String path = String.join(" > ", headingPath);
        if (path.length() > MAX_PREFIX_LENGTH) {
            path = path.substring(0, MAX_PREFIX_LENGTH) + "…";
        }
        return "【章节：" + path + "】\n";
    }

    // ------------------------------------------------------------------ 重叠与合并

    /** 给相邻块之间加字符重叠：把前一块的尾部拼到下一块开头 */
    private List<Block> applyOverlap(List<Block> blocks) {
        if (overlapChars <= 0 || blocks.size() < 2) {
            return blocks;
        }
        List<Block> result = new ArrayList<>(blocks.size());
        result.add(blocks.get(0));
        for (int index = 1; index < blocks.size(); index++) {
            Block current = blocks.get(index);
            Block prior = blocks.get(index - 1);
            if (current.protectedContent() || prior.protectedContent()) {
                result.add(current);
                continue;
            }
            String previous = prior.text();
            String tail = previous.length() <= overlapChars
                    ? previous
                    : previous.substring(previous.length() - overlapChars);
            // 重叠起点也不能落在 UTF-16 代理对中间。
            if (!tail.isEmpty() && Character.isLowSurrogate(tail.charAt(0))) tail = tail.substring(1);
            result.add(new Block("…" + tail + "\n" + current.text(), false));
        }
        return result;
    }

    /**
     * 合并过短的片段。
     *
     * <p>并入**前一块**而不是后一块：前面的块通常承载章节开头，语义更完整；
     * 而并入前面只影响前缀归属，不影响内容顺序。
     *
     * <p>合并后保留**前一块的章节路径**（内容从那里开始）。跨节合并是刻意的：
     * 一个只有两行的节单独成块会变成检索噪声，宁可让它跟着上一节走。
     */
    private List<Piece> mergeSmallPieces(List<Piece> pieces) {
        if (pieces.size() < 2) {
            return pieces;
        }
        List<Piece> result = new ArrayList<>();
        for (Piece piece : pieces) {
            if (!result.isEmpty() && piece.text().length() < minChunkChars) {
                // 注意：先 **peek** 再决定，不能先 remove 再判断 ——
                // 那样一旦体积超限，前一块就既不在 result 里、也没被重新加入，直接被丢掉。
                // 2026-10-25 实测踩到过：原文 13867 字符只索引了 7173（丢了 48%）。
                Piece previous = result.get(result.size() - 1);
                int combined = previous.text().length() + piece.text().length() + 2;
                // 上限保护：块若已明显超出目标长度，就不再并入。
                // 否则一节由很多小标题组成时，会连锁合并出一块远超目标的巨块
                if (combined <= targetChars * 3 / 2) {
                    result.set(result.size() - 1, new Piece(previous.headingPath(),
                            previous.text() + "\n\n" + piece.text()));
                    continue;
                }
            }
            result.add(piece);
        }
        return result;
    }
}
