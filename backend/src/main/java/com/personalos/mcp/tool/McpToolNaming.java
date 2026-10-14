package com.personalos.mcp.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 工具的命名与冲突判定（纯函数）。
 *
 * <p><b>为什么要前缀</b>：内置工具（{@code createTask} 等）与 MCP 工具挂在同一个
 * {@code ChatClient} 上，模型按名调用——同名即歧义，调用落到错误的工具上时症状是
 * 「结果莫名其妙」，属于最难排查的一类。前缀 {@code mcp_<server>_<tool>} 同时解决
 * 与内置工具的冲突和跨 server 同名工具的区分。
 */
public final class McpToolNaming {

    /** 统一前缀：一眼可辨「这是外部 server 的工具」 */
    public static final String PREFIX = "mcp_";

    /**
     * 装配名长度上限。数据库审计列（{@code operation_record.operation}）是 120，
     * 留出余量防长 server 名 + 长工具名越界——越界不报错，只会在审计写入时炸。
     */
    public static final int MAX_NAME_LENGTH = 100;

    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_]");

    private McpToolNaming() {
    }

    /**
     * 生成装配名：{@code mcp_<server>_<tool>}，两端都做清洗（小写 + 非法字符转下划线）。
     *
     * <p>清洗是必需的：server 名来自用户配置（可能带 {@code -} / 点 / 大写），
     * 而模型对工具名的可靠识别依赖保守字符集。
     */
    public static String prefixedName(String serverName, String toolName) {
        String name = PREFIX + sanitize(serverName) + "_" + sanitize(toolName);
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH) : name;
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        String cleaned = INVALID_CHARS.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
        // 折叠连续下划线并去掉首尾，避免 mcp_a___b 这类难看且仍合法的名字
        cleaned = cleaned.replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    /**
     * 冲突判定：MCP 装配名与内置工具名、以及 MCP 装配名之间的重名。
     *
     * @param builtinToolNames 内置工具名（各 {@code ToolContributor} 贡献的 {@code @Tool} 方法名）
     * @param mcpToolNames     全部 MCP 装配名（已加前缀）
     * @return 冲突描述列表（空表示无冲突）。每条含名字与冲突来源，供启动失败信息使用
     */
    public static List<String> conflicts(Set<String> builtinToolNames, List<String> mcpToolNames) {
        List<String> conflicts = new ArrayList<>();
        Set<String> builtin = builtinToolNames == null ? Set.of() : builtinToolNames;

        Set<String> seen = new LinkedHashSet<>();
        for (String name : mcpToolNames) {
            if (builtin.contains(name)) {
                conflicts.add(name + "（与内置工具同名）");
            }
            if (!seen.add(name)) {
                conflicts.add(name + "（多个 MCP server 产生同名工具）");
            }
        }
        return conflicts;
    }
}
