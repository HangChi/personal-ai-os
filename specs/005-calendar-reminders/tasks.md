---

description: "Task list for 阶段 4 — 日程与定时提醒"
---

# Tasks: 阶段 4 — 日程与定时提醒

**Input**: Design documents from `/specs/005-calendar-reminders/`

---

## Phase 1: 前置重构（泛化阶段 2 的确认机制）

> ⚠️ 这一步改动**已在跑**的阶段 2 代码。完成后阶段 2 的 21 个测试必须**仍然全绿**；
> 若行为发生变化，属于回归，必须修代码而不是改测试。

- [ ] T001 `ConfirmationActionHandler` SPI（`supports(action)` + `execute(payload)`）+ `ConfirmationRegistry`（按 action 路由，启动期校验每种取值都有处理器）
- [ ] T002 `ConfirmationService` / 实体 / 仓储 / DTO / 控制器 从 `agent/confirmation` 迁到 `common/confirmation`，`applyPayload` 的 `switch` 改为委派给 registry
- [ ] T003 阶段 2 的三个动作搬到 `task/confirmation/TaskConfirmationHandler`
- [ ] T004 阶段 2 的确认测试（9 例）与 Agent 工具测试（12 例）保持全绿 —— 回归验证

## Phase 2: 表与领域

- [ ] T005 `V5__create_calendar_tables.sql`：`calendar_event`、`reminder`（含唯一约束与索引）、
      以及 `task_confirmation.action` 的 CHECK 扩展（先删后建）
- [ ] T006 [P] `calendar/entity/{CalendarEventEntity, ReminderEntity, RecurrenceType, ReminderSource, ReminderStatus}`
- [ ] T007 [P] `CalendarEventRepository`（区间查询 + 扫描用查询）、`ReminderRepository`（到点未读 / upsert 忽略冲突）
- [ ] T008 `CalendarProperties`（扫描间隔、前瞻窗口、回看窗口、轮询间隔、默认提前量、区间查询上限）

## Phase 3: 重复规则引擎（纯函数，先写测试）

- [ ] T009 `RecurrenceCalculator`：`occurrences(rule, startsAt, until, from, to)` → 发生时刻列表
- [ ] T010 单元测试：`NONE` 只一次、`DAILY` 间隔 1 天、`WEEKLY` 间隔 7 天、`WEEKDAYS` **零周末**、
      `MONTHLY` **跨 12 个月每月恰好一次**、`until` 含端点、纯函数（两次计算结果逐元素相等）
- [ ] T011 单元测试边界：1/31 → 2 月末 → 3/31、闰年 2/29、平年 2/28、区间为空、`until` 早于 `startsAt`

## Phase 4: 日程 CRUD 与 REST

- [ ] T012 `CalendarEventService`：创建（含全天归一化、`endsAt >= startsAt`、`until >= startsAt` 校验）/ 区间查询（展开重复）/ 单个 / 修改 / 删除
- [ ] T013 [P] `calendar/dto/*`
- [ ] T014 `CalendarController`：`POST/GET/PATCH/DELETE /events`、`GET /events/{id}`
- [ ] T015 集成测试：创建/区间查询（含重复展开）/ 非法时间零写入 / 删除

## Phase 5: 提醒物化与轮询

- [ ] T016 `ReminderScanner.scan(Instant now)`：日程发生时刻 + 任务截止时间 → upsert 物化（忽略唯一键冲突）
- [ ] T017 `TaskService.findDueBetween(from, to)`（只读，供 `calendar` 调用；`calendar` → `task` 单向依赖）
- [ ] T018 `ReminderScheduler`（`@Scheduled(fixedDelayString)`）+ `@EnableScheduling` + 注入 `Clock`
- [ ] T019 `ReminderService`：待提醒列表（`dueOnly`）/ 标记已读（幂等）/ 取消
- [ ] T020 `ReminderController`：`GET /reminders`、`POST /reminders/{id}/read`、`POST /reminders/scan`
- [ ] T021 集成测试（**不 sleep**，直接传时间）：
      到点出现在列表、**扫描 5 次行数不变**、**模拟重启后行数不变**、过点未读仍展示、
      已完成任务不产生新提醒、**同一(来源,发生时刻)唯一**、删除日程后提醒为 0

## Phase 6: Agent 工具与确认接入

- [ ] T022 `CalendarTools`：5 个 `@Tool`（含 `deleteCalendarEvent` 走确认）
- [ ] T023 `CalendarConfirmationHandler` + `ConfirmationPayloads.DeleteCalendarEvent`
- [ ] T024 集成测试：桩模型调 `createCalendarEvent` → **数据库真实新增 1 行**；调 `deleteCalendarEvent` → **日程仍在** + 生成待确认；确认后才删除
- [ ] T025 集成测试：工具集合中仍**不存在**任何确认类工具（守住阶段 2 的不变量）

## Phase 7: 前端

- [ ] T026 [P] `api/calendar.ts`、`api/reminders.ts`、`stores/calendar.ts`、类型扩展
- [ ] T027 `views/CalendarView.vue`：日程列表（按天分组）+ 创建表单（含重复规则）+ 删除
- [ ] T028 到点提醒：30 秒轮询 + 全局提示条 + 「知道了」（标记已读）；**轮询失败静默重试，不弹错**
- [ ] T029 路由 `/calendar` + 导航；**顺手修 `HomeView.vue` 停留在阶段 0 的文案**
- [ ] T030 `type-check` + `build` 通过

## Phase 8: 验收与交付

- [ ] T031 `./mvnw -B verify` 全绿（阶段 2/3 的测试不得回归）
- [ ] T032 真机 REST 冒烟：建重复日程 → 手动触发扫描 → 待提醒列表 → 标记已读 → 删除日程 → 提醒归零
- [ ] T033 真机重启验证：重启后再次扫描，提醒行数不变
- [ ] T034 回填 `quickstart.md`；更新 README / architecture；更新 memory 并提交

---

## 依赖关系

- Phase 1（前置重构）**必须先做**：Phase 6 的日程删除确认依赖它
- Phase 2 → Phase 3 → Phase 4 → Phase 5 → Phase 6 → Phase 7 → Phase 8
- Phase 3 可先于 Phase 2 完成（`RecurrenceCalculator` 是纯函数，不依赖数据库）

## 必须覆盖的测试用例（关键不变量）

| # | 用例 | 断言口径 |
|---|---|---|
| 1 | `WEEKDAYS` 规则 | 30 天区间内落在周末的发生时刻数 **= 0** |
| 2 | `MONTHLY` 跨年 | 12 个月区间内**每月恰好 1 次**（不跳月） |
| 3 | 纯函数性 | 同输入计算两次，结果逐元素相等 |
| 4 | 扫描幂等 | 连续扫描 5 次，`reminder` 行数与 1 次相等 |
| 5 | 重启幂等 | 重建扫描器后再扫，行数**不变** |
| 6 | 任务来源 | 未完成任务产生提醒；**完成后不再产生新提醒** |
| 7 | 唯一性 | 同一 `(source_type, source_id, occurrence_at)` 行数 **= 1** |
| 8 | 删除清理 | 删除日程后该日程提醒 **= 0** |
| 9 | Agent 建日程 | 桩模型调 `createCalendarEvent` → `calendar_event` **真实 +1 行**
| 10 | Agent 删日程 | 调用后日程**仍在** + 待确认 +1；确认后日程消失 |
| 11 | 非法输入 | `endsAt < startsAt` 等 → `calendar_event` 变化 **= 0** |
| 12 | 工具集合无确认能力 | 全量扫描 `@Tool`，confirm/approve 命中数 **= 0** |
