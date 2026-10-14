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
  窗口 = [now − lookback, now + lookahead]      # 默认两端各 24h
  ⓪ 清理孤儿提醒：来源已不存在的行（见下面「自愈」一节）
  ① 对所有「与窗口相交」的日程（含重复展开），取落在窗口内的发生时刻
  ② 对 task 中「截止时间落在窗口内 且 状态 != DONE」的任务，取其截止时刻
  ③ 对每个 (来源, 发生时刻)：以 upsert 语义插入 reminder（提醒时刻 = 发生时刻 − 提前量）
```

**为什么物化所有落在窗口内的发生，而不是只物化「已经到点」的**：
若只物化到点的，那么「接下来会提醒什么」就永远查不到，用户与排查者都只能干等。
物化窗口内全部发生不会产生重复（唯一键保证），却让 `dueOnly=false` 成为可用的验证手段。
「是否展示」仍由查询时的 `remind_at <= now` 决定，与物化时机无关。

**为什么窗口两端都要外扩**：
- 向前看（lookahead）必须**覆盖提醒提前量**。否则当提前量大于窗口长度时，
  「已经该提醒、但发生时刻还在窗口外」的日程会被永久漏掉。
- 向后看（lookback）决定能弥补多久的停机。默认 24 小时足以覆盖日常重启；
  刻意不设成一周 —— 补发一周前的提醒只会制造噪音，而不是「更不丢数据」。

**为什么幂等靠唯一约束而不是「先查再插」**：
「先查再插」在并发下必然有竞态（手动触发扫描 + 定时扫描同时发生是现实可能的）。
把唯一性交给数据库，代码只需处理冲突（`ON CONFLICT DO NOTHING`）。
这也让**系统时间回拨**变得安全：判断依据是「这个时刻有没有提醒」，而不是「现在几点」。

**「过点未读仍展示」**：查询条件是 `status = PENDING AND remind_at <= now`（**不是** `= now` 附近的窄区间）。
提醒的意义在于「你还没看到它」，而不是「它恰好在这一秒到点」。

## 提醒清理：主动删除 + 扫描自愈

```
删除日程：① 先删该日程的全部提醒   ② 再删日程
删除任务：不经过提醒模块（task 不知道提醒的存在）→ 由扫描的孤儿清理兜底
扫描每次：DELETE reminder WHERE remind_at <= 窗口终点 AND 来源已不存在
```

**为什么来源删除时是「删除提醒」而不是「标记取消」**：提醒唯一的作用就是指向它的来源；
来源没了，那行连同标题快照都只是噪音。整行删除让「来源删除后不留任何提醒」
成为一条可以用单条 SQL 断言的不变量（SC-007），而不是「查询时要记得过滤掉 CANCELLED」。

**为什么还要扫描兜底（而不是只靠主动清理）**：任务删除时 `task` 模块并不知道提醒的存在，
也不该知道（否则为了这个功能引入一条反向依赖）。若只在删除路径上挂清理钩子，
那么将来任何一条新的删除路径漏挂钩子，都会留下**幽灵提醒** ——
用户会收到一条指向「已不存在的任务」的提醒，且没有任何报错。
让扫描顺手扫掉孤儿，等于让系统具备自愈能力：任何删除路径、甚至直接改数据库，都会在下一轮被纠正。

**代价**：幽灵提醒最长存在一个扫描周期（默认 60 秒）。个人场景可接受，且远优于「静默残留」。

## 偏差清单

| 编号 | 偏差 | 原因 |
|---|---|---|
| D-01 | 确认机制从 `agent` 上移到 `common` 并引入 SPI | 否则每新增一种高风险操作都要改通用服务里的 `switch`；日程删除是第二个使用方，正是抽象成立的时机 |
| D-02 | `reminder` 用多态来源而非两张表 | 用户要求两种来源都能提醒；一张表 + 来源类型，避免结构重复与 UNION 查询 |
| D-03 | 不建「提醒」与「日程/任务」的外键 | 有外键就必须级联删除，无法审计「曾经提醒过」；用 `source_id` + 标题快照 + 孤儿清理替代 |
| D-04 | 前端轮询间隔 30 秒、后端扫描间隔 60 秒 | 轮询快于扫描没有意义；两者都可配置 |
| D-05 | 来源删除时**删除**提醒而非标记取消 | 见上节；让 SC-007 成为可被单条 SQL 断言的不变量 |
| D-06 | `MONTHLY` 每次从原始起点按月份序号计算，而非迭代累加 | `plusMonths` 会把超出当月的日钳到月末；迭代累加会让 1/31 永久偏移成 3/28 |
