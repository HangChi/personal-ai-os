---
description: "Task list for 阶段 11 — 工具审计与 MCP 状态"
---

# Tasks: 阶段 11 — 工具审计与 MCP 状态

**Input**: Design documents from `/specs/011-tool-audit-and-mcp-status/`

> `[P]` 标记可并行任务。阶段串行：Phase 1/2 后端可并行，Phase 3 前端依赖 Phase 1/2 的契约。

---

## Phase 1: 工具审计（后端）

- [x] T001 `OperationRecordRepository` 追加 `JpaSpecificationExecutor<OperationRecordEntity>`
- [x] T002 [P] `agent/audit/ToolInvocationQuery`：`page`/`size`/`outcome` 收敛（对齐 `TaskQuery` 的 `DEFAULT_SIZE=20`/`MAX_SIZE=100`）
- [x] T003 `agent/audit/ToolInvocationService`：构造 Specification（**`source=TOOL` 恒定条件** + 可选 `outcome` + 可选 `conversationId`）、`Sort.by(desc createdAt)`、`Page → PageDto`
- [x] T004 `ToolInvocationDto` 加 `id`；`ToolInvocationController` 改为委托 Service
- [x] T005 `ToolInvocationControllerTest`（MockMvc）：`outcome` 未知值 → `40000`；`size` 超限收敛；无 `outcome` 时全量
- [x] T006 `ToolInvocationIntegrationTest`（Testcontainers）：**先插入 HTTP 记录再插 TOOL 记录**，断言第 1 页只含 TOOL 且 `total` 正确 —— 这条用例专门守住「过滤必须在分页前」
- [x] T007 同上：`outcome=FAILED` 筛选后 `total` 与实际 FAILED 条数一致
- [x] T008 `./mvnw -B verify` 全绿

## Phase 2: MCP 状态（后端）

- [x] T009 [P] `mcp/status/McpServerStatusDto`：`{name, available, failureReason, toolCount, autoTools}`
- [x] T010 [P] `mcp/status/McpStatusDto`：`{enabled, servers[]}`
- [x] T011 `mcp/status/McpStatusService`：`ObjectProvider<McpClientRegistry>` 兜底；`enabled` 取自 `McpProperties`；按配置顺序输出
- [x] T012 `mcp/status/McpStatusController`：`GET /api/v1/mcp/servers`，**无条件注册**
- [x] T013 `McpStatusControllerTest`：`mcp.enabled=false` → `enabled=false, servers=[]`（不 500）；
  `enabled=true` → 状态与 `autoTools` 正确；响应 JSON **不含** `env`/`command`/`args`

## Phase 3: 前端「活动」页

- [x] T014 [P] `src/api/activity.ts`：`listToolInvocations({page,size,outcome})`、`fetchMcpStatus()`；类型定义与之同文件
- [x] T015 `src/stores/activity.ts`：审计 `items/page/size/total/outcome/loading/error` + `mcp {enabled, servers, loading, error}`；
  `setOutcome` MUST 重置 `page=0`（spec US-3 场景 2）
- [x] T016 `src/views/ActivityView.vue`：MCP 状态卡片（未启用 / 已启用无 server / 逐 server 展示）+ 审计表格 + 分页 + 结果筛选
- [x] T017 `src/router/index.ts` 加 `/activity`（title「活动」）；`src/App.vue` 导航加入口
- [x] T018 `frontend/test/activity-store.test.mjs`：分页加载、`setOutcome` 重置页码、加载失败保留可读错误
- [x] T019 `npm run type-check && npm run build && npm run test:logic` 全绿

## Phase 4: 真机验证与文档

- [x] T020 起 mock + 后端（`MCP_ENABLED=false`），打开 `/activity`：显示「MCP 未启用」，审计列表能加载
- [x] T021 造几条工具调用（对话里触发 `查看待办`），刷新后条目出现，`total` 与 SQL `count(*)` 一致
- [x] T022 开 `MCP_ENABLED=true` + filesystem server 重启，页面显示「可用 / 工具数 / 只读清单」
- [x] T023 README 路线图加阶段 11；「MCP 外部工具接入」一节补「运行期状态在 /activity 可查」
- [x] T024 提交：`docs(mcp): 阶段 11 规格` + `feat(mcp): 阶段 11 工具审计与 MCP 状态`

## 完成记录

**提交**：`1d28d1d` feat(mcp): 阶段 11 工具审计与 MCP 状态（T001-T019）

**测试**
- 后端 `./mvnw -B verify`：**226** 通过（原 216 + 新增 10）
  - `ToolInvocationAuditIntegrationTest`（6）：过滤在分页前 / outcome 筛选 /
    size 收敛 / 未知 outcome 400 / 同毫秒翻页稳定 / 空库
  - `McpStatusServiceTest`（4）：未启用 / 已启用 / 注册表缺失 / 不泄漏 env
- 前端 `npm run test:logic`：**146** 断言通过（原 124 + 新增 22）；
  `type-check` 与 `build` 通过

**真机验证（Playwright，8081 + 真实 pgvector）**
- `MCP_ENABLED` 关闭：页面显示「MCP 未启用」+ 配置指引，审计列表正常加载
- `MCP_ENABLED=true` 且配置 filesystem server（`../mcp-sandbox`，14 工具，
  3 个 AUTO）+ 一个故意写错的 command：
  - sandbox 显示「可用 / 14 / directory_tree·list_directory·read_file」
  - broken 显示「不可用 / 0」+ `Client failed to initialize listing tools`
  - **应用照常启动**，降级行为与阶段 6 一致
- 审计：`total=8` 与 SQL `count(*) where source='TOOL'` 一致；
  筛选「待确认」→ 3 条且标签全为待确认；筛选「失败」→ 空态文案「没有符合筛选条件的记录」

**过程中发现并修掉的两个体验问题**
1. Element Plus 把空串当「未选择」，「全部结果」显示成英文 placeholder `Select`
   → 改用 `'ALL'` 作为显式取值
2. 空表文案不区分「一条都没有」与「筛掉了」，筛完仍说「还没有调用过工具」
   → 按 `outcome` 是否为空切换文案

**踩坑**
- `MCP_SERVERS_<名>_ARGS` 里的相对路径是相对**后端工作目录**（`backend/`）解析的，
  写成 `./mcp-sandbox` 会让 server 报 `None of the specified directories are accessible`，
  症状是「连接失败」而不是「路径错误」—— 与配置笔误的表现完全一样，容易误判为 server 本身有问题
- 5173 被旧 dev server 占用时 Vite 会静默换到 5174，而 `curl 5173` 仍走旧配置指向 8080，
  看起来像「代理没生效」。已给 `vite.config.ts` 加 `VITE_PROXY_TARGET` 覆盖
