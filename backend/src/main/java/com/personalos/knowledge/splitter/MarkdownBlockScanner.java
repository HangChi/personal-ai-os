package com.personalos.knowledge.splitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 轻量结构扫描：只确定不可截断的原文区间，不重写代码、表格或公式。 */
final class MarkdownBlockScanner {
    private static final Pattern FENCE = Pattern.compile("^(`{3,}|~{3,})(.*)$");
    private static final Pattern LIST = Pattern.compile("^([ \\t]*)(?:[-+*]|[0-9]{1,9}[.)])[ \\t]+\\S[^\n]*$");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile(
            "^\\|?\\s*:?-+:?\\s*(?:\\|\\s*:?-+:?\\s*)*\\|?$");

    record Span(int start, int end) {}
    private record Line(String text, int start, int end) {}
    private record Fence(char marker, int length) {}

    private MarkdownBlockScanner() {}

    static List<Span> blocks(String text) {
        List<Line> lines = new ArrayList<>();
        int offset = 0;
        for (String line : text.split("\n", -1)) {
            lines.add(new Line(line, offset, offset + line.length()));
            offset += line.length() + 1;
        }
        List<Span> result = new ArrayList<>();
        for (int i = 0; i < lines.size();) {
            String line = lines.get(i).text();
            String trimmed = line.strip();
            int end = i;
            Fence fence = openingFence(line);
            if (fence != null) {
                end = lines.size();
                for (int j = i + 1; j < lines.size(); j++) {
                    if (closesFence(lines.get(j).text(), fence)) {
                        end = j + 1;
                        break;
                    }
                }
            } else if (!line.isBlank() && indentation(line) >= 4
                    && (i == 0 || lines.get(i - 1).text().isBlank()
                    || HEADING.matcher(lines.get(i - 1).text().strip()).matches())) {
                // 缩进代码优先于其内部的引用/列表/公式符号。
                end = i + 1;
                while (end < lines.size()) {
                    int next = skipBlank(lines, end);
                    if (next >= lines.size() || indentation(lines.get(next).text()) < 4) break;
                    end = next + 1;
                }
            } else if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                String close = trimmed.startsWith("$$") ? "$$" : "\\]";
                end = i + 1;
                if (!trimmed.substring(2).contains(close)) {
                    while (end < lines.size() && !lines.get(end).text().strip().contains(close)) end++;
                    if (end < lines.size()) end++;
                }
            } else if (i + 1 < lines.size() && trimmed.contains("|")
                    && TABLE_SEPARATOR.matcher(lines.get(i + 1).text().strip()).matches()) {
                end = i + 2;
                while (end < lines.size() && !lines.get(end).text().isBlank()
                        && lines.get(end).text().contains("|")) end++;
            } else if (trimmed.contains("\t") && i + 1 < lines.size()
                    && lines.get(i + 1).text().strip().contains("\t")) {
                // DOCX 表格提取结果以制表符分列，不依赖 Markdown 分隔行。
                end = i + 1;
                while (end < lines.size() && lines.get(end).text().strip().contains("\t")) end++;
            } else if (trimmed.startsWith(">")) {
                end = i + 1;
                while (end < lines.size()) {
                    int next = skipBlank(lines, end);
                    if (next >= lines.size()) break;
                    String following = lines.get(next).text();
                    if (!following.strip().startsWith(">")
                            && (next != end || startsBlock(following) || LIST.matcher(following).matches())) break;
                    end = next + 1;
                }
            } else if (LIST.matcher(line).matches()) {
                int base = indentation(line);
                end = i + 1;
                while (end < lines.size()) {
                    int next = skipBlank(lines, end);
                    if (next >= lines.size()) break;
                    String following = lines.get(next).text();
                    boolean continuation = indentation(following) > base || LIST.matcher(following).matches();
                    // 无空行的普通续行属于同一项；新的块级结构仍结束列表。
                    if (!continuation && next == end && !startsBlock(following)) continuation = true;
                    if (!continuation) break;
                    end = next + 1;
                }
            }
            if (end > i) {
                result.add(new Span(lines.get(i).start(), lines.get(end - 1).end()));
                i = end;
            } else {
                i++;
            }
        }
        return result;
    }

    /** 行内代码也不能被字符兜底切断；相同长度的反引号才闭合。 */
    static List<Span> inlineCode(String text) {
        List<Span> runs = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            if (text.charAt(i) != '`') { i++; continue; }
            int start = i;
            while (i < text.length() && text.charAt(i) == '`') i++;
            runs.add(new Span(start, i));
        }
        // 预先定位同长度的下一个分隔符，避免大量未闭合反引号反复扫描全文。
        int[] next = new int[runs.size()];
        Map<Integer, Integer> byLength = new HashMap<>();
        for (int i = runs.size() - 1; i >= 0; i--) {
            Span run = runs.get(i);
            int length = run.end() - run.start();
            next[i] = byLength.getOrDefault(length, -1);
            byLength.put(length, i);
        }
        List<Span> result = new ArrayList<>();
        for (int i = 0; i < runs.size();) {
            Span run = runs.get(i);
            if (escaped(text, run.start()) || next[i] < 0) { i++; continue; }
            int closing = next[i];
            result.add(new Span(run.start(), runs.get(closing).end()));
            i = closing + 1;
        }
        return result;
    }

    static List<Span> headingProtection(String text) {
        List<Span> result = new ArrayList<>();
        int offset = 0;
        for (Span block : blocks(text)) {
            for (Span inline : inlineCode(text.substring(offset, block.start()))) {
                result.add(new Span(offset + inline.start(), offset + inline.end()));
            }
            result.add(block);
            offset = block.end();
        }
        for (Span inline : inlineCode(text.substring(offset))) {
            result.add(new Span(offset + inline.start(), offset + inline.end()));
        }
        return result;
    }

    static String trimOutsideBlocks(String text, List<Span> spans) {
        int start = text.length() - text.stripLeading().length();
        int end = text.stripTrailing().length();
        if (!spans.isEmpty()) {
            start = Math.min(start, spans.getFirst().start());
            end = Math.max(end, spans.getLast().end());
        }
        return text.substring(Math.min(start, end), end);
    }

    private static Fence openingFence(String line) {
        if (indentation(line) > 3) return null;
        Matcher match = FENCE.matcher(line.strip());
        if (!match.matches()) return null;
        String marker = match.group(1);
        if (marker.charAt(0) == '`' && match.group(2).contains("`")) return null;
        return new Fence(marker.charAt(0), marker.length());
    }

    private static boolean closesFence(String line, Fence fence) {
        if (indentation(line) > 3) return false;
        String value = line.strip();
        int count = 0;
        while (count < value.length() && value.charAt(count) == fence.marker()) count++;
        return count >= fence.length() && value.substring(count).isBlank();
    }

    private static boolean startsBlock(String line) {
        String s = line.strip();
        return s.startsWith("#") || s.startsWith(">") || s.startsWith("$$") || s.startsWith("\\[")
                || openingFence(line) != null || s.contains("|") || s.contains("\t");
    }

    private static int skipBlank(List<Line> lines, int index) {
        while (index < lines.size() && lines.get(index).text().isBlank()) index++;
        return index;
    }

    private static int indentation(String line) {
        int width = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') width++;
            else if (line.charAt(i) == '\t') width += 4 - width % 4;
            else break;
        }
        return width;
    }

    private static boolean escaped(String text, int index) {
        int slashes = 0;
        while (index > 0 && text.charAt(--index) == '\\') slashes++;
        return slashes % 2 != 0;
    }
}
