# Implementation Plan: 阶段 4 — 日程与定时提醒

**Branch**: `005-calendar-reminders` | **Date**: 2026-10-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-calendar-reminders/spec.md`

## Summary

新增 `calendar` 模块：日程 CRUD + 重复规则计算（纯函数）+ 提醒物化与轮询查询。调度采用 **DB 状态驱动的定时扫描**：`@Scheduled` 每 60 秒调用一次 `scan(Instant now)`，扫描**同时**看日程的近期发生时刻与任务的近期截止时间，把应有的提醒以「插入并忽略冲突」的方式物化到 `reminder` 表（唯一键 `(source_type, source_id, occurrence_at)` 保证幂等）。前端每 30 秒轮询「到点未读」列表。日程删除复用阶段 2 的人类确认机制 —— 为此先把该机制**泛化并上移到 `common/confirmation`**（引入 `ConfirmationActionHandler` SPI）。

## Technical Context

**Language/Version**: Java 21；TypeScript 5.9.3

**Primary Dependencies**：
- Spring `@Scheduled`（`@EnableScheduling`）+ 注入的 `Clock`（`Clock.systemDefaultZone()`）
- 复用：Spring Data JPA、Flyway、Validation、阶段 2 的 `ToolExecutor`/`ToolContributor`/确认机制

**Storage**：PostgreSQL 17；新增表 `calendar_event`、`reminder`；迁移 `V5`

**Testing**：JUnit 5（重复规则用纯单元测试）+ `@SpringBootTest` + Testcontainers（扫描幂等、任务来源、删除清理）

**Target Platform**：本地单用户自托管

**Project Type**：Web application（Monorepo：backend + frontend）

**Performance Goals**：单次扫描 < 50ms（个人量级）；区间查询 < 100ms

**Constraints**：重启后行为一致；所有时间相关测试不依赖真实等待；扫描必须幂等

**Scale/Scope**：个人日程量级（数百事件、数千提醒）；阶段 4 新增约 40 个文件

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先，拒绝过度设计 | ✅ 通过 | 不引入 RRULE 库、消息队列、分布式锁、推送通道；重复规则只做 4 类；调度就是一个 `@Scheduled` + 一次 SQL upsert |
| II. 边界即安全 | ✅ 通过 | 日程删除（不可逆）由 Agent 发起时必经人工确认；查询区间与条数有上限；扫描只物化，不改动源数据 |
| III. 分层与写路径收敛 | ✅ 通过 | 新增 `calendar`（entity/repository/service/controller/dto + recurrence/scheduler）；写操作全在 Service；`calendar` 只**只读**依赖 `task` 的查询能力，方向单一 |
| IV. 数据变更可追溯 | ✅ 通过 | `V5__create_calendar_tables.sql` 单一迁移（含新增确认动作取值的 CHECK 扩展）；`ddl-auto=validate` |
| V. 可验证的交付 | ✅ 通过 | SC-001~003 用纯函数逐元素比对；SC-004/005 用「扫描 N 次 vs 1 次行数相等」与「重启后行数不变」断言幂等；SC-007 断言删除后提醒为 0 |

**结论**：无违例。但有一次**涉及阶段 2 代码的前置重构**（确认机制泛化），需保证阶段 2 的 21 个测试保持全绿。

## Project Structure

### Documentation (this feature)

```text
specs/005-calendar-reminders/
├── spec.md  ├── plan.md  ├── research.md  ├── data-model.md
├── quickstart.md  ├── tasks.md
├── contracts/calendar-api.md
└── checklists/requirements.md
```

### Source Code (repository root)

```text
backend/src/main/java/com/personalos/
├── common/confirmation/                     # 阶段 2 机制上移 + 泛化（重构）
│   ├── ConfirmationActionHandler.java       # SPI：业务模块注册动作处理器
│   ├── ConfirmationService.java             # 只负责令牌/有效期/单次性/原子占位
│   ├── ConfirmationRegistry.java            # 按 action 路由到 handler
│   ├── ConfirmationAction.java              # 增加 DELETE_CALENDAR_EVENT
│   ├── ConfirmationStatus.java / ConfirmationPayloads.java / 实体 / 仓储 / DTO / 控制器
├── task/confirmation/TaskConfirmationHandler.java   # 阶段 2 的三个动作搬到这里
├── calendar/
│   ├── entity/{CalendarEventEntity, ReminderEntity, RecurrenceType, ReminderSource, ReminderStatus}.java
│   ├── repository/{CalendarEventRepository, ReminderRepository}.java
│   ├── recurrence/RecurrenceCalculator.java  # 纯函数：规则 + 区间 → 发生时刻列表
│   ├── service/{CalendarEventService, ReminderService, ReminderScanner}.java
│   ├── scheduler/ReminderScheduler.java      # @Scheduled → scanner.scan(clock.instant())
│   ├── dto/{CalendarEventDto, CreateEventRequest, UpdateEventRequest, EventQuery,
│   │         ReminderDto, ReminderScanResult}.java
│   ├── controller/{CalendarController, ReminderController}.java
│   ├── confirmation/CalendarConfirmationHandler.java
│   └── tool/{CalendarTools, CalendarToolContributor}.java
├── task/service/TaskService.java             # 新增只读查询：findDueBetween(from, to)
└── config/ (启用 @EnableScheduling)

backend/src/main/resources/db/migration/V5__create_calendar_tables.sql
backend/src/test/java/com/personalos/
├── calendar/recurrence/RecurrenceCalculatorTest.java   # 纯单元测试（无 Spring）
├── calendar/CalendarEventIntegrationTest.java
└── calendar/ReminderScanIntegrationTest.java

frontend/src/
├── api/calendar.ts  stores/calendar.ts
└── views/CalendarView.vue                    # 日程列表 + 创建 + 到点提醒（30s 轮询）
```

**Structure Decision**：沿用「单 Maven 模块 + 按包分模块」。确认机制的泛化是本阶段唯一的跨界重构，
判断依据与阶段 3 把 `ToolExecutor` 上移相同：**跨模块的公共机制不应留在某个业务模块里**。

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| 把确认机制上移到 `common` 并引入 SPI | 日程删除要接入确认；否则每加一种高风险操作都要改 `ConfirmationService` 里那个 `switch` | 让 `calendar` 直接依赖 `agent.confirmation`：方向颠倒（业务模块依赖另一个业务模块的私有实现），且下一种操作还要再依赖一次 |
| 新增 `calendar_event` / `reminder` 两张表 | 日程与提醒是不同生命周期的对象：日程被删后提醒仍需可追溯（不可能靠一张表表达） | 只用一张表：要么日程删除后提醒跟着消失（丢失审计），要么让提醒字段寄生在日程上（无法承载"任务来源"的提醒） |
| `reminder` 用 `(source_type, source_id, occurrence_at)` 多态来源 | 用户已明确要求「日程与任务都能提醒」；多态来源让一张表承载两种来源，而不是建两张几乎相同的表 | 建 `event_reminder` + `task_reminder` 两张表：结构重复、查询要 UNION、前端要合并两路结果 |
| 扫描是「物化」而非「即时计算」 | 「已读」状态无法从日程推导；且物化后才能把幂等约束落到数据库唯一键上 | 查询时现算：无法承载已读、每次查询都要重算重复规则 |
