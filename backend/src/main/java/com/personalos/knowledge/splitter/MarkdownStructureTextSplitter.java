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
 *   <li>**超长节按段落递归切**：按空行分段，逐段累积到目标长度；单段仍然超长时，
 *       才用 {@link TokenTextSplitter} 硬切兜底。</li>
 *   <li>**标题路径前缀**：每块前面加 {@code 【章节：…】}。这让检索能命中章节信息，
 *       也让模型看到片段在原文中的位置。</li>
 *   <li>**短块合并**：低于最小字符数的块并入相邻块，避免出现只有半句话的噪声块
 *       （评测中发现 29 / 92 字符的样例文档片段会挤进检索结果）。</li>
 * </ol>
 *
 * <h2>重叠</h2>
 * 支持可配置的字符重叠（默认 0）。结构切分已在自然边界断开，重叠的收益主要来自
 * 跨节的上下文衔接；开太大则块会膨胀、总块数上升。
 */
public class MarkdownStructureTextSplitter extends TextSplitter {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*#*$");

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
            for (String raw : splitSection(section)) {
                if (!raw.isBlank()) {
                    pieces.add(new Piece(section.headingPath(), raw.strip()));
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

        for (String line : text.split("\n")) {
            Matcher matcher = HEADING.matcher(line.strip());
            if (matcher.matches()) {
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
                body.append(line.strip()).append('\n');
                continue;
            }
            body.append(line).append('\n');
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

    private List<String> splitSection(Section section) {
        String body = section.body().strip();
        if (body.isEmpty()) {
            return List.of();
        }
        if (body.length() <= targetChars) {
            return List.of(body);
        }

        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.split("\n\\s*\n")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > targetChars) {
                // 单段就超长（常见于无换行的长文或代码块）：硬切兜底
                if (!current.isEmpty()) {
                    pieces.add(current.toString());
                    current.setLength(0);
                }
                pieces.addAll(hardSplit(trimmed));
                continue;
            }
            if (current.length() + trimmed.length() + 2 > targetChars && !current.isEmpty()) {
                pieces.add(current.toString());
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }
        if (!current.isEmpty()) {
            pieces.add(current.toString());
        }

        return applyOverlap(pieces);
    }

    /**
     * 兜底：单段超过目标长度时按**字符**切，并尽量在句末断开。
     *
     * <p>为什么不用 {@link TokenTextSplitter} 兜底：本切分器的目标长度是**字符**，
     * 而 token 切分器的 800 token 在中文上可能落到 3000 字符 —— 实测出现过
     * 3089 字符的块，远超 1200 的目标，且和「按字符控制块大小」的语义不一致。
     * 一个原则：既然对外承诺的是字符数，兜底也必须以字符为准。
     */
    private List<String> hardSplit(String text) {
        List<String> pieces = new ArrayList<>();
        String remaining = text;
        while (remaining.length() > targetChars) {
            int cut = findBreakPoint(remaining);
            pieces.add(remaining.substring(0, cut).strip());
            remaining = remaining.substring(cut).strip();
        }
        if (!remaining.isEmpty()) {
            pieces.add(remaining);
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
    private List<String> applyOverlap(List<String> blocks) {
        if (overlapChars <= 0 || blocks.size() < 2) {
            return blocks;
        }
        List<String> result = new ArrayList<>(blocks.size());
        result.add(blocks.get(0));
        for (int index = 1; index < blocks.size(); index++) {
            String previous = blocks.get(index - 1);
            String tail = previous.length() <= overlapChars
                    ? previous
                    : previous.substring(previous.length() - overlapChars);
            result.add("…" + tail + "\n" + blocks.get(index));
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
