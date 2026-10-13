/**
 * Agent 模块。
 *
 * <p>职责：Agent 编排与工具（Tool Calling）注册。
 *
 * <p>安全边界（宪法原则 II）：模型 MUST NOT 直接访问数据库或执行任意代码，
 * 所有副作用必须经由本模块暴露的显式 Java 接口；破坏性操作必须有人工确认。
 *
 * <p>阶段 0 仅占位。将在阶段 2（任务 CRUD 与 Tool Calling）开始填充。
 */
package com.personalos.agent;
