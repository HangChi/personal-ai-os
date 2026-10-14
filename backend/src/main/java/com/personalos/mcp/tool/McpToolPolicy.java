package com.personalos.mcp.tool;

import java.util.Set;

/**
 * MCP 工具的策略分级。
 *
 * <p><b>为什么分级</b>：确认机制的价值在于「每次弹出都意味着风险」。全部 AUTO 等于把外部系统的
 * 写权限直接交给模型（违反宪法原则 II）；全部 CONFIRM 会把用户训练成无脑点确认，
 * 反向瓦解确认机制。只有按工具分开。
 *
 * <p><b>默认 CONFIRM（fail-closed）</b>：新 server 接入时漏配的代价是多弹一次确认，
 * 而不是一次未授权的写入——安全默认值必须让错误方向指向「更安全」。
 */
public enum McpToolPolicy {

    /** 只读工具：直接执行，不产生待确认记录 */
    AUTO,

    /** 变更类工具：生成待确认事项，用户确认后才真正执行 */
    CONFIRM;

    /**
     * 策略解析（顺序即契约）。
     *
     * @param toolName  **原始**工具名（server 声明的名字，不是加了前缀的装配名）
     * @param autoTools 配置中声明为只读的工具名清单
     * @return 命中清单 → AUTO；否则 → CONFIRM
     */
    public static McpToolPolicy resolve(String toolName, Set<String> autoTools) {
        return autoTools != null && autoTools.contains(toolName) ? AUTO : CONFIRM;
    }
}
