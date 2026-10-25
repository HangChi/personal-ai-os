# Feature Specification: 阶段 11 — 工具审计与 MCP 状态

**Feature Branch**: `011-tool-audit-and-mcp-status`

**Created**: 2026-10-25

**Status**: Draft

**Input**: 用户需求：「帮我逐个完成」的第 2 项 —— 后端已有审计端点但前端零引用（死接口）；
MCP 只有 `.env` 配置，运行期状态完全不可见。

## 背景

自托管用户对系统有两个绕不开的疑问：

1. **「助手到底替我做了什么？」** 阶段 2 就把每次工具调用写进了 `operation_record`
   （工具名、结果、耗时、会话），也提供了 `GET /api/v1/agent/tool-invocations`。
   但前端从来没有引用过它 —— 能力存在，入口不存在，等于没有。
2. **「我接入的外部工具现在是什么状态？」** 阶段 6 的 MCP 客户端会在启动期逐个连 server、
   把不可用的标记出来、按 `AUTO_TOOLS` 分级，但这些信息只出现在**启动那一瞬间的日志**里。
   想确认「filesystem server 现在连上没有、几个工具、哪些会弹确认」，只能翻日志或重启。

本阶段把这两块运行期事实变成可查看的界面。**不新增任何写操作**，全部是只读观测。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 查看助手做过什么（Priority: P1）

作为用户，我希望有一个「活动」页，按时间倒序列出助手调用过的每个工具，看到工具名、结果、耗时、
所属会话，并能翻页。

**Why this priority**：这是本阶段的主要价值。审计数据早就在库里，缺的只是一个入口；
它同时是「确认机制是否真的在拦」的唯一事后证据。

**Independent Test**：让助手执行几次工具（或直接调审计接口），打开活动页，
条目数、顺序、结果标签与数据库 `operation_record`（`source='TOOL'`）一致；翻到第 2 页内容不重复。

**Acceptance Scenarios**：

1. **Given** 库里有工具调用记录，**When** 打开活动页，**Then** 按 `createdAt` 倒序显示，
   每条含工具名、结果标签、耗时、发生时间、所属会话（无会话时显示「—」）。
2. **Given** 记录超过一页，**When** 点击下一页，**Then** 取到第 2 页且与第 1 页无重复条目；
   总数显示为**过滤后**的真实总数，不是本页条数。
3. **Given** 库里同时有 `source='TOOL'` 与 `source='HTTP'` 的记录，**When** 查看活动页，
   **Then** 只出现 `TOOL` 的记录（HTTP 幂等记录不属于「助手做的事」）。
4. **Given** 后端不可达，**When** 打开活动页，**Then** 显示可读的错误提示，**不是**空白表格。
5. **Given** 记录数为 0，**When** 打开活动页，**Then** 显示空态说明（「助手还没有调用过工具」）。

---

### User Story 2 - 查看 MCP 接入状态（Priority: P1）

作为用户，我希望在同一页看到每个 MCP server 的当前状态：是否连上、暴露了几个工具、
哪些工具是只读直行、哪些需要我确认；连不上时看到原因。

**Why this priority**：与 US-1 同属「运行期事实可见」。MCP 的故障刻意只在日志里降级
（阶段 6 R-07：server 连不上不影响应用启动），这在设计上是对的，但代价是
**用户没有别的地方能发现它挂了**。没有这块，降级就变成了静默失效。

**Independent Test**：配置一个可用的 filesystem server → 页面显示「可用」与工具数；
再故意把 command 改成不存在的程序 → 页面显示「不可用」并给出原因，而应用照常启动。

**Acceptance Scenarios**：

1. **Given** `MCP_ENABLED=true` 且有一个连上的 server，**When** 打开活动页，
   **Then** 该 server 显示「可用」、工具数、以及 `AUTO_TOOLS` 里声明的只读工具清单。
2. **Given** 某个 server 连接失败，**When** 打开活动页，**Then** 该 server 显示「不可用」与失败原因摘要。
3. **Given** `MCP_ENABLED` 未开启（默认），**When** 打开活动页，**Then** 明确显示「未启用」
   并给出配置示例，**不是** 500 或空白。
4. **Given** 配置里含 `ENV_*`（可能装密钥），**When** 读取状态接口，**Then** 响应中
   **不含** `env`、`command`、`args`（本机路径与密钥都不该出现在页面上）。

---

### User Story 3 - 按结果筛选（Priority: P3）

作为用户，我希望能只看「失败 / 被拒绝 / 被去重」的调用，用来排查「为什么助手说做了但其实没做」。

**Why this priority**：没有它审计仍可用（全部列出即可）；有它才便于定位问题。

**Independent Test**：库里有 SUCCESS 与 FAILED 两种记录，选「失败」后列表只剩 FAILED。

**Acceptance Scenarios**：

1. **Given** 存在多种 outcome，**When** 选择某个结果筛选，**Then** 列表只含该结果，总数同步变化。
2. **Given** 筛选后当前页超出范围，**When** 切换筛选，**Then** 页码重置为第 1 页
   （否则用户会停在一个空页上，误以为没有数据）。

## Edge Cases

| 输入 | 期望行为 |
|---|---|
| `page` 为负 / `size` 为 0 或极大 | 收敛到合法值，不报 500（沿用 `TaskQuery` 的收敛口径） |
| `outcome` 传入未知取值 | `40000` 参数错误，提示允许值 |
| 审计记录数超过 10 万 | 分页在 SQL 层完成，不加载全量到内存 |
| `mcp.enabled=true` 但 `servers` 为空 | 返回 `enabled=true, servers=[]`，前端显示「已启用但未配置 server」 |
| 工具名超过 120 字符 | 不可能（写入时已受列长度约束），但前端不得因长名撑破布局 |
| `durationMs` 为 null（如 REQUIRES_CONFIRMATION 未真正执行） | 显示「—」，不显示 0 |

## Requirements *(mandatory)*

- **FR-001**：审计列表 MUST 改为 `page`/`size` 分页，响应 MUST 为 `PageDto`，
  `total` MUST 是**过滤后**的总数。
- **FR-002**：审计列表 MUST 只返回 `source=TOOL` 的记录，且过滤 MUST 在分页之前完成。
  > 现有实现是「先按 limit 取一页 → 再内存 filter」，会导致「一页凑不满 50 条」且
  > `total` 无从得知。这是本阶段必须修掉的缺陷，不是重构。
- **FR-003**：审计 DTO MUST 增加 `id`（前端列表 key 与未来详情页的锚点）。
- **FR-004**：审计接口 MUST 支持按 `outcome` 筛选；未知取值 MUST 报参数错误。
- **FR-005**：新增 `GET /api/v1/mcp/servers`，返回 `{enabled, servers[]}`，
  每个 server 含 `name` / `available` / `failureReason` / `toolCount` / `autoTools`。
- **FR-006**：`mcp.enabled=false` 时该端点 MUST 返回 `enabled=false, servers=[]`，
  **MUST NOT** 因缺少 `McpClientRegistry` Bean 而启动失败或 500。
- **FR-007**：状态响应 MUST NOT 包含 `env`、`command`、`args`（密钥与本机路径）。
- **FR-008**：前端 MUST 新增「活动」页（`/activity`），含审计列表与 MCP 状态两块；
  导航栏 MUST 有入口。
- **FR-009**：所有加载失败 MUST 以可读文案呈现，MUST NOT 留空白或暴露堆栈。
- **FR-010**：本阶段 MUST NOT 新增任何写接口、不做审计记录的删除（审计只读是刻意的）。

## Success Criteria

- **SC-001**：`./mvnw -B verify` 全绿，新增测试覆盖「过滤在分页前」「outcome 筛选」
  「MCP 关闭时端点可用」三条易错路径。
- **SC-002**：`npm run type-check && npm run test:logic` 全绿，新增 store 测试覆盖
  分页切换、筛选重置页码、加载失败三条路径。
- **SC-003**：真机打开 `/activity`，能同时看到审计条目与 MCP 状态；
  关掉 `MCP_ENABLED` 重启后，页面显示「未启用」而不是报错。
- **SC-004**：审计列表的 `total` 与
  `SELECT count(*) FROM operation_record WHERE source='TOOL'` 一致。

## Assumptions

- 审计记录量级为个人使用（万级以内），但分页仍放在 SQL 层 —— 现在的实现方式（内存 filter）
  一旦修好就没有理由再引入「加载全量」的退化。
- MCP 状态的刷新靠「重新加载页面」而非轮询：server 生命周期与进程一致，
  页面上放一个刷新按钮比 30 秒轮询更划算（宪法原则 I）。
- 不做「审计详情页」（点开看 `response_payload`）：payload 可能很大，
  首版只解决「有没有发生」，不解决「返回了什么」。
