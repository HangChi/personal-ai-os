package com.personalos.common.confirmation;

/**
 * 需要人工确认的操作类型。
 *
 * <p>每个取值都对应一种「不可逆或影响用户计划」的动作。
 *
 * <p><b>新增取值时必须一并提供处理器</b>：{@link ConfirmationRegistry} 会在启动期校验
 * 「每个取值都有 {@link ConfirmationActionHandler}」，否则应用起不来。
 * 与其等用户点了「确认」才发现没人处理，不如启动就拦住。
 * 另外 {@code confirmation.action} 上有 CHECK 约束，新增取值需要配套迁移。
 */
public enum ConfirmationAction {
    /** 删除任务（可含批量） */
    DELETE_TASK,
    /** 修改任务截止时间 */
    SCHEDULE_TASK,
    /** 修改任务优先级 */
    SET_PRIORITY,
    /** 删除日程（可含批量） */
    DELETE_CALENDAR_EVENT,
    /**
     * 调用外部 MCP server 的变更类工具（阶段 6）。
     *
     * <p>具体是哪个 server 的哪个工具在 payload 里——MCP 工具名是运行期动态的，
     * 用通用动作桥接动态内容与静态枚举（research.md R-05）。
     * 处理器：{@code mcp} 模块的 {@code McpConfirmationHandler}（无条件注册：
     * MCP 关闭时也不能让「枚举有值但无处理器」把应用挡在启动期）。
     */
    MCP_TOOL_CALL
}
