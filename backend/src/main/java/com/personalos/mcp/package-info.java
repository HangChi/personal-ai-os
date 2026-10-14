/**
 * MCP 客户端接入（阶段 6）。
 *
 * <p>把外部 MCP server 的工具经显式 Java 适配层并入既有 {@code ChatClient} 装配链：
 * 配置与生命周期在 {@code config} / {@code client}，工具适配（前缀 / 策略 / 审计）在
 * {@code tool}，变更类工具的人工确认在 {@code confirmation}。
 *
 * <p>本模块不依赖任何其它业务模块，只依赖 {@code common}（确认机制 / 审计 / 装配点）——
 * 与阶段 3 把 {@code ToolExecutor} 上移时的判断一致：横切能力放 common，业务能力放自己的包。
 */
package com.personalos.mcp;
