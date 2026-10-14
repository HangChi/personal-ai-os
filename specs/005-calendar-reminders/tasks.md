---

description: "Task list for 阶段 4 — 日程与定时提醒"
---

# Tasks: 阶段 4 — 日程与定时提醒

**Input**: Design documents from `/specs/005-calendar-reminders/`

---

## Phase 1: 前置重构（泛化阶段 2 的确认机制）✅

- [x] T001 `ConfirmationActionHandler` SPI + `ConfirmationRegistry`（启动期校验：每个动作有处理器、无重复注册）
- [x] T002 确认机制从 `agent/confirmation` 迁到 `common/confirmation`，`applyPayload` 的 `switch` 改为委派；表名 → `confirmation`（V5）
- [x] T003 阶段 2 的三个动作落到 `task/confirmation/`
- [x] T004 回归验证：87 测试全绿、零回归（提交 `e3a709e`）

## Phase 2: 表与领域 ✅

- [x] T005 `V7__create_calendar_tables.sql`（`calendar_event` + `reminder` + 唯一约束 + 索引 + CHECK 扩展）
- [x] T006 [P] `calendar/entity/{CalendarEventEntity, ReminderEntity, RecurrenceType, ReminderSource, ReminderStatus}`
- [x] T007 [P] `CalendarEventRepository`（区间粗筛）、`ReminderRepository`（UPSERT / 按来源删除 / 孤儿清理）
- [x] T008 `CalendarProperties` + `CalendarConfig`（`@EnableScheduling` / `ZoneId` Bean / `Clock` Bean）

## Phase 3: 重复规则引擎 ✅

- [x] T009 `RecurrenceCalculator`（纯函数；本地时区加减；MONTHLY 从原始起点按月份序号计算）
- [x] T010 单元测试：NONE/DAILY/WEEKLY/WEEKDAYS/MONTHLY、纯函数性、until 含端点
- [x] T011 单元测试边界：1/31→2/28→**3/31**、闰年 2/29、区间非法、起点晚于区间、null 类型、**夏令时不漂移**

## Phase 4: 日程 CRUD 与 REST ✅

- [x] T012 `CalendarEventService`（全天归一化、起止成对校验、区间查询展开重复、删除连带清理提醒）
- [x] T013 [P] `calendar/dto/*`（定义视图 / 发生实例 / 创建 / 修改 / 删除应答）
- [x] T014 `CalendarController`（`POST/GET/PATCH/DELETE /events`、`GET /events/{id}`）
- [x] T015 集成测试：重复展开、全天归一化、非法时间零写入

## Phase 5: 提醒物化与轮询 ✅

- [x] T016 `ReminderScanner.scan(Instant)`：孤儿清理 → 日程发生 → 任务截止 → UPSERT
- [x] T017 `TaskService.findDueBetween(from, to)`（只读，`calendar` → `task` 单向依赖）
- [x] T018 `ReminderScheduler`（`@Scheduled` + `@ConditionalOnProperty` 可关闭）
- [x] T019 `ReminderService`（待提醒列表 / 幂等已读）
- [x] T020 `ReminderController`（`GET /reminders`、`POST /{id}/read`、`POST /scan`）
- [x] T021 集成测试：到点出现在列表、**扫描 5 次行数不变**、**新实例扫描行数不变**、过点未读仍展示、已完成任务不产生、唯一性、删除清理、**孤儿自愈**、已读幂等

## Phase 6: Agent 工具与确认接入 ✅

- [x] T022 `CalendarTools`（5 个 `@Tool`；`deleteCalendarEvent` 走确认）
- [x] T023 `CalendarConfirmationService` + `CalendarConfirmationHandler` + 新增动作取值
- [x] T024 集成测试：`createCalendarEvent` 真实改库；`deleteCalendarEvent` 只生成待确认、确认后才删除
- [x] T025 既有测试守住「工具集合中不存在确认类能力」

## Phase 7: 前端 ✅

- [x] T026 [P] `api/calendar.ts`、`api/reminders.ts`、`stores/calendar.ts`、类型扩展
- [x] T027 `CalendarView.vue`（按天分组列表 + 创建表单含重复规则与提前量 + 删除）
- [x] T028 到点提醒：30 秒轮询 + 「知道了」；**轮询失败静默**，不打扰用户
- [x] T029 路由 `/calendar` + 导航；**顺手修掉 `HomeView` 停留在阶段 0 的文案**（改为真实能力总览）
- [x] T030 `type-check` 退出码 0 + `build` 成功（新增 `CalendarView` chunk）

## Phase 8: 验收与交付 ✅

- [x] T031 `./mvnw -B verify` → **115 测试全绿**（阶段 2/3 零回归）
- [x] T032 真机 REST 冒烟：重复展开零周末、扫描物化、已读幂等、删除后提醒归零
- [x] T033 真机重启验证：重启前后提醒行数一致
- [x] T034 回填 `quickstart.md`；更新 README / architecture；更新 memory 并提交

---

## 实现偏差记录

| 编号 | 偏差 | 原因 |
|---|---|---|
| K1-D1 | V6 追加修正 V5 遗漏的主键约束改名，**不改写 V5** | V5 已应用，改写会让校验和与 `flyway_schema_history` 不一致 → 应用拒绝启动。**已应用的迁移只追加，绝不改写** |
| K2-D2 | 来源删除时**删除**提醒（而非标记取消），并让扫描清理孤儿 | ① 提醒唯一的作用是指向来源，来源没了就是噪音；② 更重要：任务删除时 `task` 并不知道提醒的存在，**会留下指向已删任务的幽灵提醒**（用户会收到且无任何报错）。扫描自愈让任何删除路径都能被纠正 |
| K3-D3 | `dueOnly=false` 的语义修正为「返回全部」 | 原实现只返回将来的，与参数名「不按到点过滤」不符，会误导排查者 |
| K4-D4 | 新增 `common/ai/tool/ToolArguments` 统一时间解析 | 任务工具与日程工具各写一份必然漂移，会出现「一个接受 2026-10-17、另一个报错」的困惑 |
| K5-D5 | 新增 `src/test/resources/application.properties` 关闭调度 | `@Scheduled` 在测试中同样会跑，写入 `reminder` 会与各用例清理逻辑干扰，产生难复现的偶发失败 |
| K6-D6 | `MONTHLY` 每次从**原始起点**按月份序号计算 | `plusMonths` 会把超出当月的日钳到月末；迭代累加会让 1/31 永久偏移成 3/28（已写成单元测试断言） |
| K7-D7 | 提醒物化**不限于已到点**，窗口内全部物化 | 否则 `dueOnly=false` 永远查不到东西，「提前看到会提醒什么」与「验证扫描是否生效」都无从谈起 |
| K8-D8 | 源删除的清理（K2-D2）—— 明确记录为**设计决定**而非权宜 | 代价是幽灵提醒最长存在一个扫描周期（默认 60 秒），远优于「静默残留」 |

### 实现过程中发现并修复的自身缺陷

1. **删除任务会留下幽灵提醒**：阶段 2 的 `deleteAll` 不知道提醒的存在（也不该知道），
   而阶段 4 引入了任务提醒 → 删掉任务后其提醒仍会到点弹出。改为「扫描清理孤儿」自愈。
2. **`dueOnly=false` 语义与参数名不符**：见 K3-D3，由真机冒烟的输出发现。
3. **手工 `new` 的扫描器 `@Transactional` 不生效**：测试里「模拟重启」时暴露 ——
   声明式事务基于代理，非 Bean 实例上的注解不会生效。测试改为显式包一层 `TransactionTemplate`
   （这本身也提醒了代理边界）。

### 编译器帮我们拦住的一处

给 `ConfirmationAction` 增加 `DELETE_CALENDAR_EVENT` 后，`TaskConfirmationHandler` 里
**没有 `default` 分支的穷尽 switch 立刻编译失败**。
这正是「switch 表达式不写 default」的价值：新增枚举取值会被强制确认，
而不是等到运行期才发现某个动作无人处理。

## 推荐提交

```
feat(calendar): 阶段 4 日程与定时提醒（T001-T034）

- calendar 模块：日程 CRUD + 重复规则（纯函数）+ 提醒物化 + 轮询查询
- 重复规则只做 NONE/DAILY/WEEKLY/MONTHLY/WEEKDAYS，自实现不引日历库；
  MONTHLY 当月无该日取当月最后一天，且从原始起点按月份序号计算（避免永久偏移）
- 调度：DB 状态驱动的周期扫描，@Scheduled 触发；时间经 Clock 注入，扫描入口显式收 Instant，
  因此全部时间相关测试不 sleep；重启后行为一致
- 幂等：reminder 唯一约束 (source_type, source_id, occurrence_at) + ON CONFLICT DO NOTHING，
  连续扫描 5 次与新实例扫描行数均不变
- 清理：日程删除连带删提醒；扫描清理「来源已不存在」的孤儿提醒
  （修掉「删了任务却仍收到它的提醒」这一幽灵行为）
- 首次把确认机制用于第二个模块：日程删除走人工确认，通用机制无需改动
- 前端：日程页（按天分组 + 重复规则 + 提前量）+ 30 秒轮询提醒，「知道了」标记已读
- 测试：./mvnw -B verify → 115 测试全绿（新增 28 例）
```
