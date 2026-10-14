# Data Model: 阶段 4 — 日程与定时提醒

## 表结构（Flyway `V5`）

### `calendar_event`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK DEFAULT `uuid_generate_v4()` | |
| `title` | `varchar(200)` | NOT NULL | 标题 |
| `description` | `text` | | 描述 |
| `location` | `varchar(200)` | | 地点 |
| `all_day` | `boolean` | NOT NULL DEFAULT false | 全天标记 |
| `starts_at` | `timestamptz` | NOT NULL | 开始时间 |
| `ends_at` | `timestamptz` | NOT NULL | 结束时间 |
| `recurrence_type` | `varchar(20)` | NOT NULL DEFAULT `'NONE'` | `NONE` / `DAILY` / `WEEKLY` / `MONTHLY` / `WEEKDAYS` |
| `recurrence_until` | `timestamptz` | | 重复截止（**含**该时刻）；为空表示无限 |
| `reminder_lead_minutes` | `integer` | NOT NULL DEFAULT 15 | 提前提醒量（分钟） |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |
| `updated_at` | `timestamptz` | NOT NULL DEFAULT `now()` | |

**索引**：`(starts_at)` —— 支撑「按时间区间查询」这一最常用形态。
**CHECK**：`ends_at >= starts_at`（FR-003）、`recurrence_type` 取值、`reminder_lead_minutes >= 0`。
**索引**：`(recurrence_type, starts_at)` —— 扫描时筛「需要展开重复的事件」。

### `reminder`

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | `uuid` | PK DEFAULT `uuid_generate_v4()` | |
| `source_type` | `varchar(20)` | NOT NULL | `CALENDAR_EVENT` / `TASK` |
| `source_id` | `uuid` | NOT NULL | 来源标识 |
| `title` | `varchar(300)` | NOT NULL | **标题快照** |
| `occurrence_at` | `timestamptz` | NOT NULL | 发生时刻（日程的开始时间 / 任务的截止时间） |
| `remind_at` | `timestamptz` | NOT NULL | 提醒时刻 = 发生时刻 − 提前量 |
| `status` | `varchar(20)` | NOT NULL DEFAULT `'PENDING'` | `PENDING` / `READ` / `CANCELLED` |
| `created_at` | `timestamptz` | NOT NULL DEFAULT `now()` | 物化时间 |
| `read_at` | `timestamptz` | | 读取时间 |

**唯一约束**：`(source_type, source_id, occurrence_at)` —— **幂等的基础**（FR-011 / SC-008）。
**索引**：`(status, remind_at)` —— 支撑「到点且未读」这一唯一的热查询形态。

**为什么存标题快照**：提醒是「历史事实」的记录。日程被改名或删除后，
这条提醒仍应说明「当时提醒的是什么」。若只存 `source_id`，删除后就只剩一串 UUID。

**为什么 `occurrence_at` 而不是「第几次」**：重复日程的每一次发生都是一个独立的时间点；
用时间点作键天然唯一，且与「时间回拨」无关 —— 判断依据是「这个时刻有没有提醒」，而不是「现在几点」。

## 重复规则的语义（必须写清楚，否则会被当成 bug）

| 类型 | 语义 | 边界裁定 |
|---|---|---|
| `NONE` | 只有一次 | — |
| `DAILY` | 每天同一时刻 | — |
| `WEEKLY` | 每周同一星期几、同一时刻 | — |
| `MONTHLY` | 每月同一「日」同一时刻 | **当月无该日时取当月最后一天**（1/31 → 2/28 或 2/29 → 3/31）。不跳过该月 |
| `WEEKDAYS` | 周一至周五同一时刻 | 周六周日不产生发生时刻 |

**`MONTHLY` 的裁定理由**：用户设「每月 31 日交报表」，2 月没有 31 日。
若跳过 2 月，用户会整月收不到提醒且不知道为什么；取当月最后一天是符合直觉的做法。
**这条必须写进代码注释**，否则下一个维护者会以为 `min(day, lastDayOfMonth)` 是个笔误。

**重复截止**：`recurrence_until` **含**该时刻 —— 语义是「到这个时刻为止」，
写成开区间会让用户最后一期收不到提醒。

## 扫描（物化）的判定逻辑

```
scan(now):
  窗口 = [now, now + lookahead]            # 默认 lookahead = 24h
  ① 对所有「与窗口相交」的日程（含重复展开），取落在窗口内的发生时刻
  ② 对 task 中「截止时间落在窗口内 且 状态 != DONE」的任务，取其截止时刻
  ③ 对每个 (来源, 发生时刻)：
       提醒时刻 = 发生时刻 − 提前量
       若 提醒时刻 <= now：以 upsert 语义插入 reminder（冲突则忽略）
```

**为什么窗口要包含「已经到点」的时刻**：
若只看 `[now, now+24h]`，那么当扫描间隔大于提醒提前量时（例如提前量 5 分钟、扫描间隔 60 分钟），
提醒时刻会**在两次扫描之间悄悄过去**，于是永远不生成 —— 用户丢提醒且毫无线索。
因此窗口的左边界要往回收一段（默认回 24h），保证「刚过点但还没物化」的提醒也能补上。
已物化的靠唯一键天然去重，重复扫描不会产生第二条。

**「过点未读仍展示」**：查询条件是 `status = PENDING AND remind_at <= now`（**不是** `= now` 附近的窄区间）。
提醒的意义在于「你还没看到它」，而不是「它恰好在这一秒到点」。

## 删除的清理顺序（与阶段 3 同构）

```
日程删除：① 先取消/删除该日程的提醒（按 source_type + source_id）  ② 再删日程
```

理由与阶段 3 一致：反过来一旦第 ② 步之后失败，残留提醒将无法按 `source_id` 定位清理
（`source_id` 指向一条已不存在的日程）。当前顺序下最坏情况是「日程还在但提醒被清了」，
下一轮扫描会重新物化 —— 这是**可恢复**的失败。

## 与阶段 2 确认机制的衔接（前置重构）

`ConfirmationAction` 新增取值 `DELETE_CALENDAR_EVENT`。该枚举取值在 `task_confirmation.action`
上有 CHECK 约束，因此 V5 迁移需要：**先删除旧 CHECK，再新增包含新取值的 CHECK**。

同时 `ConfirmationPayloads` 新增 `DeleteCalendarEvent(List<UUID> eventIds, List<String> titles)`
（带标题快照，理由同 `reminder.title`）。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 确认机制从 `agent` 上移到 `common` 并引入 SPI | 否则每新增一种高风险操作都要改通用服务里的 `switch`；日程删除是第二个使用方，正是抽象成立的时机 |
| D-02 | `reminder` 用多态来源而非两张表 | 用户要求两种来源都能提醒；一张表 + 来源类型，避免结构重复与 UNION 查询 |
| D-03 | 不建「提醒」与「日程」的外键 | 提醒要能在日程删除后留存（历史事实），有外键就必须级联删除；用 `source_id` + 标题快照替代 |
| D-04 | 前端轮询间隔 30 秒、后端扫描间隔 60 秒 | 轮询快于扫描没有意义；两者都可配置 |
