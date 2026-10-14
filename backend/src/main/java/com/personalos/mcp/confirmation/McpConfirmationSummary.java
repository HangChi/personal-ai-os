package com.personalos.mcp.confirmation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MCP 待确认事项的摘要生成（纯函数）。
 *
 * <p><b>summary 必须由服务端依据 payload 生成</b>，不采信模型输出的文本——
 * 否则模型可以写一个「写个不重要的临时文件」的描述，而 payload 里却是别的路径。
 *
 * <p>关键参数的选取是**确定性**的（键排序后取前 3 个），不按「哪个键更像路径」
 * 猜测——猜测会在参数集合变化时给出不稳定摘要，让用户对不上号。
 */
public final class McpConfirmationSummary {

    /** 单个参数值的展示上限（超长截断，避免摘要刷屏） */
    private static final int MAX_VALUE_LENGTH = 80;

    /** 最多展示几个参数 */
    private static final int MAX_ARGS = 3;

    private McpConfirmationSummary() {
    }

    public static String summarize(McpToolCall payload) {
        StringBuilder summary = new StringBuilder("MCP 工具调用确认：server=")
                .append(payload.server())
                .append("，工具=")
                .append(payload.tool());
        String args = formatArguments(payload.arguments());
        if (!args.isEmpty()) {
            summary.append("，").append(args);
        }
        return summary.toString();
    }

    private static String formatArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        // TreeMap 保证键序稳定：同样的参数永远得到同样的摘要
        Map<String, Object> sorted = new TreeMap<>(arguments);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (parts.size() >= MAX_ARGS) {
                parts.add("…等 " + sorted.size() + " 个参数");
                break;
            }
            parts.add(entry.getKey() + "=" + truncate(String.valueOf(entry.getValue())));
        }
        return String.join("，", parts);
    }

    private static String truncate(String value) {
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() > MAX_VALUE_LENGTH
                ? singleLine.substring(0, MAX_VALUE_LENGTH) + "…"
                : singleLine;
    }
}
