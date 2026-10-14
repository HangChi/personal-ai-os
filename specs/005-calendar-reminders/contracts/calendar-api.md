# HTTP API 契约：阶段 4 — 日程与提醒

**Base Path**：`/api/v1/calendar`（日程）、`/api/v1/reminders`（提醒）

沿用既有：`40000` 参数校验失败 / `40004` 资源不存在 / `409` 类业务冲突 / `50000` 内部错误。
本阶段**不新增错误码** —— 所有失败都能用既有语义表达。

---

## 日程

### `POST /api/v1/calendar/events` — 创建日程

```json
{
  "title": "周会",
  "description": "讨论本周进展",
  "location": "会议室 A",
  "allDay": false,
  "startsAt": "2026-10-15T09:00:00+08:00",
  "endsAt": "2026-10-15T10:00:00+08:00",
  "recurrenceType": "WEEKLY",
  "recurrenceUntil": "2026-12-31T23:59:59+08:00",
  "reminderLeadMinutes": 15
}
```

| 字段 | 必填 | 说明 |
|---|---|---|
| `title` | 是 | 1~200 字 |
| `description` | 否 | ≤2000 字 |
| `location` | 否 | ≤200 字 |
| `allDay` | 否 | 默认 false |
| `startsAt` / `endsAt` | 是 | ISO-8601；`endsAt` 不得早于 `startsAt` |
| `recurrenceType` | 否 | `NONE`（默认）/ `DAILY` / `WEEKLY` / `MONTHLY` / `WEEKDAYS` |
| `recurrenceUntil` | 否 | 重复截止（含）；不得早于 `startsAt` |
| `reminderLeadMinutes` | 否 | 默认 15，取值 0~10080（7 天） |

**响应 201**：`data` 为 CalendarEventDto。

**全天日程**：`allDay=true` 时，`startsAt` 必须是当天 00:00、`endsAt` 必须是次日 00:00
（服务端会归一化并在响应中回显归一化后的值）。

### `GET /api/v1/calendar/events` — 按区间查询（含重复展开）

| 查询参数 | 必填 | 说明 |
|---|---|---|
| `from` | 否 | 区间起点（ISO-8601）；省略为「今天 00:00」 |
| `to` | 否 | 区间终点；省略为起点 + 7 天 |
| `page` | 否 | 页码，从 0 开始（2026-10-14 起替代 `limit`） |
| `size` | 否 | 默认 100，上限 500 |

**响应 200**：`data` 为分页对象 `PageDto`（2026-10-14 技术债清偿起，原 `limit` 参数与裸数组响应已废弃）。
`items` 为**发生实例**数组（按 `startsAt` 升序），`total` 为区间内展开后的全量实例数：

```json
{
  "items": [
    {
      "eventId": "3f2a…",
      "title": "周会",
      "location": "会议室 A",
      "allDay": false,
      "startsAt": "2026-10-22T09:00:00+08:00",
      "endsAt": "2026-10-22T10:00:00+08:00",
      "recurrenceType": "WEEKLY",
      "recurring": true,
      "reminderLeadMinutes": 15
    }
  ],
  "page": 0,
  "size": 100,
  "total": 4
}
```

> **为什么废弃 `limit`**：重复日程在大区间下的实例数很容易超过上限，静默截断会让用户
> 翻不到「下周」——`total` 报告全量实例数后，「还有多少」不再是秘密。

> **为什么返回「发生实例」而不是「日程」**：重复日程在区间内有多个时刻，
> 前端要按时间顺序渲染。服务端展开后返回，前端无需理解重复规则 ——
> 重复语义只有一处实现（`RecurrenceCalculator`），不会前后端各写一遍。

### `GET /api/v1/calendar/events/{id}` — 单个日程（原始定义，不展开）

### `PATCH /api/v1/calendar/events/{id}` — 修改（字段语义同创建；仅更新出现的字段）

### `DELETE /api/v1/calendar/events/{id}` — 删除

**响应 200**：`data` 为 `{ "deleted": true, "deletedReminders": 3 }`

**说明**：
- 提醒**随日程一起删除**（不是标记取消）——提醒唯一的作用就是指向它的来源，来源没了它就是噪音。
  这让「删除后该日程的提醒条数为 0」成为一条可用单条 SQL 断言的不变量（SC-007）。
- 删除顺序是**先删提醒、再删日程**（反过来失败会留下无法定位的孤儿提醒）。
- 人工直接调用**不需要确认**（发起者是人本身，与阶段 2 的任务删除一致）。
  由 Agent 发起的删除走确认流程。
- 扫描还会顺手清理「来源已不存在」的孤儿提醒 —— 兜底那些不经过本接口的删除路径
  （典型是任务删除：`task` 模块并不知道提醒的存在，也不该知道）。

---

## 提醒

### `GET /api/v1/reminders` — 待提醒列表

| 查询参数 | 必填 | 说明 |
|---|---|---|
| `status` | 否 | 默认 `PENDING`；可选 `READ` / `CANCELLED` |
| `dueOnly` | 否 | 默认 `true`：只返回 `remind_at <= now` 的；设为 `false` 返回**全部**（含将来要提醒的） |
| `limit` | 否 | 默认 50，上限 200 |

**`dueOnly=false` 的用途**：让用户能提前看到「接下来会提醒什么」，
也便于人工验证「扫描是否已经物化了下一条」——否则只能干等到点。
它的语义是**不按到点过滤**（返回全部），而不是「只返回将来的」。

**响应 200**：

```json
{
  "code": 0, "message": "OK",
  "data": [
    {
      "id": "9c1e…",
      "sourceType": "CALENDAR_EVENT",
      "sourceId": "3f2a…",
      "title": "周会",
      "occurrenceAt": "2026-10-15T09:00:00+08:00",
      "remindAt": "2026-10-15T08:45:00+08:00",
      "status": "PENDING"
    }
  ],
  "timestamp": "…"
}
```

**`dueOnly=false` 的用途**：让用户能提前看到「接下来会提醒什么」，
也便于人工验证「扫描是否已经物化了下一条」——否则只能干等到点。

### `POST /api/v1/reminders/{id}/read` — 标记已读

**响应 200**：`data` 为 `{ "id": "…", "status": "READ" }`

**幂等**：重复标记同一提醒返回 200 且状态仍为 `READ`（**不**报 409）。
理由：用户可能重复点击，或轮询期间页面重发；把它当错误只会制造无意义的报错。

### `POST /api/v1/reminders/scan` — 手动触发一次扫描（排错用）

**响应 200**：`data` 为 `{ "created": 4, "skipped": 12, "pruned": 1, "scannedAt": "…" }`

**说明**：这是**排错设施**而非业务接口 —— 让用户不必等到下一个调度周期就能验证扫描是否正常。
它不是「触发提醒」：提醒是否展示仍由 `remind_at <= now` 决定。

`pruned` 是清理掉的**孤儿提醒**条数（来源已被删除）。它让「删掉任务后提醒仍会弹」这类问题
在下一轮扫描被自动纠正 —— 不需要在每条删除路径上挂清理钩子（漏掉一条就静默出问题）。

---

## Agent 工具

| 工具 | 需确认 | 说明 |
|---|---|---|
| `createCalendarEvent` | 否 | 创建日程 |
| `findCalendarEvents` | 否 | 按区间查询（含重复展开） |
| `findDueReminders` | 否 | 查看当前到点未读的提醒 |
| `markReminderRead` | 否 | 标记已读（可逆） |
| `deleteCalendarEvent` | **是** | 删除日程（不可逆）—— 只创建待确认事项 |

`deleteCalendarEvent` 返回 `REQUIRES_CONFIRMATION`，**不返回令牌**；
确认途径仍只存在于面向人类的接口（阶段 2 的结构性防线不变）。
