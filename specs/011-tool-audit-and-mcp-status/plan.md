# Implementation Plan: 阶段 11 — 工具审计与 MCP 状态

**Branch**: `011-tool-audit-and-mcp-status` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

把两块已经在库里/在内存里、却没有任何界面的运行期事实暴露出来：

1. **工具审计**：改造已有的 `GET /api/v1/agent/tool-invocations` —— 加分页、加 `outcome` 筛选、
   加 `id`，并**修掉一个真实缺陷**：原实现先用 `PageRequest` 取一页、再在内存里过滤 `source=TOOL`，
   导致「一页凑不满」「total 无从得知」。修法是把过滤下推到 SQL，`total` 由数据库给出。
2. **MCP 状态**：新增 `GET /api/v1/mcp/servers`，把 `McpClientRegistry` 里的状态快照读出来，
   并在 `mcp.enabled=false` 时正常返回「未启用」而不是 500。

前端新增一个「活动」页（`/activity`）：上半 MCP 状态、下半工具调用记录。
**零新依赖、零迁移、零新表、零写接口。**

## Technical Context

- **语言/框架**：Java 21，Spring Boot 4.1.1，Spring Web MVC，Spring Data JPA
- **存储**：PostgreSQL 17（`operation_record` 结构不变，**无迁移**）
- **前端**：Vue 3 + TypeScript + Pinia + Element Plus
- **测试**：JUnit 5 + MockMvc + Testcontainers（后端）；`node --test` 风格的 `.mjs`（前端 store 逻辑）
- **新增依赖**：无
- **规模**：后端 2 个 Controller（1 改 1 新）+ 3 个 DTO + 1 个 Service + Repository 加接口；
  前端 2 个 API 模块 + 1 个 store + 1 个视图 + 路由/导航各一行

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 零新依赖、零迁移。**不**做审计详情页（`response_payload` 可能很大，R-05）、**不**给 MCP 状态加轮询（server 生命周期与进程一致，刷新按钮足够，R-06）、**不**为审计建独立仓储层 |
| II. 边界即安全 | ✅ 通过 | 全部只读，不新增任何副作用路径；审计**不提供删除**（审计可被删就不能作为事后证据）。MCP 状态响应剔除 `env`/`command`/`args`（R-04），密钥与本机路径不出现在页面上 |
| III. 分层与写路径收敛 | ✅ 通过 | 无写操作。Controller 不直接碰 Repository 以外的逻辑——查询条件构造放 Service；对外一律 DTO，不暴露 `OperationRecordEntity` |
| IV. 数据变更可追溯 | ✅ 通过 | **本阶段无结构变更**，不新增迁移，`ddl-auto=validate` 不变 |
| V. 可验证的交付 | ✅ 通过 | 三条易错路径各有一条集成测试（过滤在分页前 / outcome 筛选 / MCP 关闭时端点可用）；前端 store 测试覆盖分页与筛选交互；真机打开 `/activity` 双向验证（开/关 `MCP_ENABLED`） |
| 工作流：阶段严格串行 | ✅ 通过 | 011 是编号 10 之后的第一个新阶段；编号 5 的空置状态不受影响 |

## Project Structure

### 改动清单（Documentation → Source Code）

**文档**
- `specs/011-tool-audit-and-mcp-status/spec.md`（本阶段需求）
- `specs/011-tool-audit-and-mcp-status/plan.md`（本文件）
- `specs/011-tool-audit-and-mcp-status/tasks.md`
- `README.md`：路线图加一行、目录结构补 `mcp` 状态说明
- `docs/architecture.md`：若涉及模块边界描述则补一句（本阶段预计不需要）

**后端**
```
agent/audit/ToolInvocationController.java     改：分页 + outcome 筛选 + 只能 GET
agent/audit/ToolInvocationDto.java            改：加 id
agent/audit/ToolInvocationQuery.java          新：page/size/outcome 收敛（对齐 TaskQuery）
agent/audit/ToolInvocationService.java        新：构造 Specification、排序、Page→PageDto
common/idempotency/OperationRecordRepository  改：extends JpaSpecificationExecutor
mcp/status/McpStatusController.java           新：GET /api/v1/mcp/servers
mcp/status/McpStatusDto.java                  新：{enabled, servers[]}
mcp/status/McpServerStatusDto.java            新：{name, available, failureReason, toolCount, autoTools}
mcp/status/McpStatusService.java              新：ObjectProvider 兜底 + 配置投影
```

**前端**
```
src/api/activity.ts        新：listToolInvocations(params) / fetchMcpStatus()
src/stores/activity.ts     新：审计分页 + 筛选 + MCP 状态，各自独立的 loading/error
src/views/ActivityView.vue 新：MCP 状态卡片 + 审计表格 + 分页
src/router/index.ts        改：加 /activity
src/App.vue                改：导航加「活动」
frontend/test/activity-store.test.mjs  新
```

### 不动的部分

- `operation_record` 表结构与写入路径（`IdempotencyService`、`ToolExecutor`）完全不动 ——
  本阶段只读它。
- `McpClientRegistry` 的连接/关闭逻辑不动，只是把已有的 `statuses()` 读出来。
- 不改任何既有接口的语义（审计接口没有前端使用者，改造无兼容负担）。

## Phase 0 / Phase 1 结论

### R-01 分页放 SQL，不放内存

`PageDto.of(all, page, size)` 的哲学是「先排好序再切片」，任务模块用它是因为数据量小。
审计表不一样：它**只增不减**（本阶段刻意不做删除），会随使用持续增长。
更关键的是，现有实现的错误顺序（先分页后过滤）本身就必须改，
顺手改成 SQL 过滤 + SQL 分页没有额外成本，还顺带解决了未来规模问题。

`PageDto` 的形状保持不变，只是 `total` 改由数据库给，`items` 只含本页。

### R-02 用 `JpaSpecificationExecutor` 而不是 JPQL 动态条件

与 `TaskRepository` 保持同一套写法。`outcome` / `conversationId` 都是可选条件，
用 JPQL 写 `(:outcome is null or outcome = :outcome)` 在 Postgres 上对枚举参数的类型推断很敏感
（会退化成 `text` 比较而静默不命中）——这个坑在阶段 2 已经踩过一次，不再重犯。

### R-03 MCP 状态端点不加 `@ConditionalOnProperty`

`McpClientRegistry` 本身是 `@ConditionalOnProperty(mcp.enabled=true)`。
如果 Controller 也跟着加条件，`MCP_ENABLED=false` 时端点**不存在**（404），
前端就没法区分「未启用」与「路径写错了」。

正确做法：Controller 无条件注册，用 `ObjectProvider<McpClientRegistry>` 取 Bean，
取不到就返回 `enabled=false, servers=[]`。
这与阶段 6 给 `ConfirmationActionHandler` 定的规则一致：**机制性 Bean 不能因为开关而消失**。

### R-04 状态响应里不放 `env` / `command` / `args`

- `env` 是 `MCP_SERVERS_<NAME>_ENV_API_KEY` 的落点，**可能装着第三方密钥**（宪法原则 II）。
- `command` / `args` 含本机绝对路径，属于不必要的暴露。

页面需要的是「连上没有、几个工具、哪些只读」，这三样分别对应
`available` / `toolCount` / `autoTools`，足够了。
`autoTools` 是用户自己写在配置里的清单，不敏感。

### R-05 不做审计详情页

`response_payload` 存的是工具返回的完整 JSON，可能很大（如目录树、检索结果全文）。
首版目标是把「有没有发生」变成可见；「返回了什么」需要设计折叠/截断与体积上限，
属于独立的体验问题，不在本阶段。

### R-06 MCP 状态不轮询

Server 的连接状态在应用生命周期内基本不变（连上就一直连着，连不上就一直标记不可用，
除非重启进程）。30 秒轮询换来的信息量接近于零，却持续打后端。
页面上放一个「刷新」按钮，与首页健康检查的交互一致。

## Complexity Tracking

无违例项。本阶段没有引入任何需要论证的复杂度。
