# HTTP API 契约：阶段 2 — 任务与确认

所有响应沿用统一信封（恰好 4 个字段）。新增错误码：

| code | 含义 | HTTP |
|---|---|---|
| 40001 | 幂等键与请求内容不一致（`IDEMPOTENCY_KEY_CONFLICT`） | 409 |
| 40002 | 确认已失效或已被使用（`CONFIRMATION_NOT_ACTIVE`） | 409 |

沿用既有：`40000` 参数校验失败 / `40004` 资源不存在 / `40900` 业务冲突 / `50000` 内部错误。

---

## 任务

### `POST /api/v1/tasks` — 创建任务

**可选请求头**：`Idempotency-Key: <8~120 字符>`（提供时启用 HTTP 层幂等）

**请求体**：

```json
{
  "title": "交周报",
  "description": "含本周进展与风险",
  "priority": "HIGH",
  "dueAt": "2026-10-17T10:00:00Z"
}
```

`title` 必填（1~200 字）；`priority` 取 `LOW|MEDIUM|HIGH`；`dueAt` 为 ISO-8601。
`description` ≤ 2000 字。

**响应 201**：`data` 为 TaskDto（见下）。

### `GET /api/v1/tasks` — 查询任务

**查询参数**（全部可选）：`status`、`keyword`（匹配标题/描述）、`limit`（默认 20，上限 100）

**响应 200**：`data` 为 TaskDto 数组，按「未完成优先 + 截止时间升序 + 创建时间倒序」排列。

### `GET /api/v1/tasks/{id}` — 单个任务

**响应 200**：TaskDto。**404**：不存在。

### `PATCH /api/v1/tasks/{id}` — 修改任务

**可选请求头**：`Idempotency-Key`

**请求体**（字段全部可选，仅更新出现的字段）：

```json
{ "title": "交周报（终版）", "description": "…", "priority": "MEDIUM", "dueAt": "2026-10-18T10:00:00Z", "status": "DOING" }
```

`dueAt` 传 `null` 表示清除截止时间。

### `POST /api/v1/tasks/{id}/complete` — 标记完成

**响应 200**：TaskDto（`status=DONE`，`completedAt` 已写入）。

### `POST /api/v1/tasks/{id}/reopen` — 取消完成

**响应 200**：TaskDto（`status=TODO`，`completedAt` 为空）。

### `DELETE /api/v1/tasks/{id}` — 删除任务

**说明**：**人工直接调用时不需要确认** —— 发起者就是人本身（FR-024）。
由 Agent 发起的删除走确认流程（见下）。

**响应 200**：`data` 为 `null`。**404**：不存在。

**TaskDto**：

```json
{
  "id": "0f2b…",
  "title": "交周报",
  "description": "含本周进展与风险",
  "status": "TODO",
  "priority": "HIGH",
  "dueAt": "2026-10-17T10:00:00Z",
  "completedAt": null,
  "createdAt": "2026-10-14T01:00:00Z",
  "updatedAt": "2026-10-14T01:00:00Z",
  "overdue": false
}
```

`overdue` 由服务端计算（未完成且 `dueAt` 早于当前时间），避免各客户端各算各的。

### 幂等行为

| 情形 | 行为 |
|---|---|
| 同键 + 同参数，重复请求 | 不重复执行，返回**与首次完全一致**的响应 |
| 同键 + **不同参数** | `409 / 40001`，提示键与内容不一致 |
| 未提供键 | 普通请求，无幂等保证 |

---

## 人工确认

> 这些接口**只面向人类**。Agent 的工具集合中不存在任何能调用它们的工具（SC-003）。

### `GET /api/v1/agent/confirmations` — 列出待确认操作

**查询参数**：`conversationId`（可选）、`status`（默认 `PENDING`）

**响应 200**：数组，含 `id`、`token`、`action`、`summary`、`affectedCount`、`expiresAt`。

```json
{
  "id": "9c1e…",
  "token": "b7f3a9…",
  "action": "DELETE_TASK",
  "summary": "删除 1 个任务：交周报",
  "affectedCount": 1,
  "expiresAt": "2026-10-14T01:10:00Z"
}
```

### `POST /api/v1/agent/confirmations/{id}/confirm` — 确认并执行

**请求体**：`{ "token": "b7f3a9…" }`

**响应 200**：`data` 为 `{ "executed": true, "affectedCount": 1, "summary": "已删除 1 个任务" }`

**失败**：
- 记录不存在 → `404 / 40004`
- 令牌不匹配 / 非 `PENDING` / 已过期 → `409 / 40002`

### `POST /api/v1/agent/confirmations/{id}/cancel` — 取消

**请求体**：`{ "token": "b7f3a9…" }`　**响应 200**：`data` 为 `null`

---

## 工具调用日志

### `GET /api/v1/agent/tool-invocations` — 查询工具执行日志

**查询参数**：`conversationId`（可选）、`limit`（默认 50，上限 200）

**响应 200**：

```json
[
  {
    "operation": "createTask",
    "outcome": "SUCCESS",
    "durationMs": 12,
    "conversationId": "3f2a…",
    "createdAt": "2026-10-14T01:02:03Z"
  }
]
```

`outcome` 取值与含义：`SUCCESS` 真实执行成功 / `REQUIRES_CONFIRMATION` 已生成待确认 / `REJECTED` 入参被拒 / `DUPLICATE` 命中幂等未重复执行 / `FAILED` 执行报错。
