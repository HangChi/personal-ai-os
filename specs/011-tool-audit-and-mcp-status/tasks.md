---
description: "Task list for 阶段 11 — 工具审计与 MCP 状态"
---

# Tasks: 阶段 11 — 工具审计与 MCP 状态

**Input**: Design documents from `/specs/011-tool-audit-and-mcp-status/`

> `[P]` 标记可并行任务。阶段串行：Phase 1/2 后端可并行，Phase 3 前端依赖 Phase 1/2 的契约。

---

## Phase 1: 工具审计（后端）

- [ ] T001 `OperationRecordRepository` 追加 `JpaSpecificationExecutor<OperationRecordEntity>`
- [ ] T002 [P] `agent/audit/ToolInvocationQuery`：`page`/`size`/`outcome` 收敛（对齐 `TaskQuery` 的 `DEFAULT_SIZE=20`/`MAX_SIZE=100`）
- [ ] T003 `agent/audit/ToolInvocationService`：构造 Specification（**`source=TOOL` 恒定条件** + 可选 `outcome` + 可选 `conversationId`）、`Sort.by(desc createdAt)`、`Page → PageDto`
- [ ] T004 `ToolInvocationDto` 加 `id`；`ToolInvocationController` 改为委托 Service
- [ ] T005 `ToolInvocationControllerTest`（MockMvc）：`outcome` 未知值 → `40000`；`size` 超限收敛；无 `outcome` 时全量
- [ ] T006 `ToolInvocationIntegrationTest`（Testcontainers）：**先插入 HTTP 记录再插 TOOL 记录**，断言第 1 页只含 TOOL 且 `total` 正确 —— 这条用例专门守住「过滤必须在分页前」
- [ ] T007 同上：`outcome=FAILED` 筛选后 `total` 与实际 FAILED 条数一致
- [ ] T008 `./mvnw -B verify` 全绿

## Phase 2: MCP 状态（后端）

- [ ] T009 [P] `mcp/status/McpServerStatusDto`：`{name, available, failureReason, toolCount, autoTools}`
- [ ] T010 [P] `mcp/status/McpStatusDto`：`{enabled, servers[]}`
- [ ] T011 `mcp/status/McpStatusService`：`ObjectProvider<McpClientRegistry>` 兜底；`enabled` 取自 `McpProperties`；按配置顺序输出
- [ ] T012 `mcp/status/McpStatusController`：`GET /api/v1/mcp/servers`，**无条件注册**
- [ ] T013 `McpStatusControllerTest`：`mcp.enabled=false` → `enabled=false, servers=[]`（不 500）；
  `enabled=true` → 状态与 `autoTools` 正确；响应 JSON **不含** `env`/`command`/`args`

## Phase 3: 前端「活动」页

- [ ] T014 [P] `src/api/activity.ts`：`listToolInvocations({page,size,outcome})`、`fetchMcpStatus()`；类型定义与之同文件
- [ ] T015 `src/stores/activity.ts`：审计 `items/page/size/total/outcome/loading/error` + `mcp {enabled, servers, loading, error}`；
  `setOutcome` MUST 重置 `page=0`（spec US-3 场景 2）
- [ ] T016 `src/views/ActivityView.vue`：MCP 状态卡片（未启用 / 已启用无 server / 逐 server 展示）+ 审计表格 + 分页 + 结果筛选
- [ ] T017 `src/router/index.ts` 加 `/activity`（title「活动」）；`src/App.vue` 导航加入口
- [ ] T018 `frontend/test/activity-store.test.mjs`：分页加载、`setOutcome` 重置页码、加载失败保留可读错误
- [ ] T019 `npm run type-check && npm run build && npm run test:logic` 全绿

## Phase 4: 真机验证与文档

- [ ] T020 起 mock + 后端（`MCP_ENABLED=false`），打开 `/activity`：显示「MCP 未启用」，审计列表能加载
- [ ] T021 造几条工具调用（对话里触发 `查看待办`），刷新后条目出现，`total` 与 SQL `count(*)` 一致
- [ ] T022 开 `MCP_ENABLED=true` + filesystem server 重启，页面显示「可用 / 工具数 / 只读清单」
- [ ] T023 README 路线图加阶段 11；「MCP 外部工具接入」一节补「运行期状态在 /activity 可查」
- [ ] T024 提交：`docs(mcp): 阶段 11 规格` + `feat(mcp): 阶段 11 工具审计与 MCP 状态`

## 完成记录

（实现后回填：提交哈希、测试数、真机验证结论）
