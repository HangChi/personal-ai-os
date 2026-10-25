---
description: "Task list for 阶段 13 — 日历月视图"
---

# Tasks: 阶段 13 — 日历月视图

**Input**: Design documents from `/specs/013-calendar-month-view/`

---

## Phase 1: 日期纯函数（先修 bug）

- [ ] T001 `src/lib/calendar-dates.ts`：`localDayKey(iso)` —— 按本地时区取 `YYYY-MM-DD`
- [ ] T002 同文件：`buildMonthCells(cursor, today)` —— 固定 42 格，周一为第一列，跨月 `inMonth=false`
- [ ] T003 同文件：`monthRange(cursor)` —— 本月 1 日 00:00 ~ 次月 1 日 00:00（本地）→ ISO
- [ ] T004 同文件：`shiftMonth(cursor, delta)` —— 处理 12 月 +1 → 次年 1 月
- [ ] T005 `frontend/test/calendar-dates.test.mjs`：时区归属（构造 UTC 串断言本地日期）、
      42 格、跨月灰格、月份边界、闰年 2 月

## Phase 2: store 改造

- [ ] T006 `groupedByDay` 改用 `localDayKey`（**修 UTC 分组缺陷**）
- [ ] T007 `loadOccurrences` 记录 `windowFrom` / `windowTo`；无参调用复用窗口
- [ ] T008 `loadOccurrences` 支持 `sizeOverride`（月视图一次拉 500 条，后端上限）
- [ ] T009 `test/calendar-store.test.mjs` 补：无参刷新复用上次窗口（不回到默认窗口）

## Phase 3: 月视图

- [ ] T010 `CalendarView` 加 `viewMode: 'list' | 'month'`，默认 `list`
- [ ] T011 月视图模板：月份标题 + 上一月/今天/下一月 + 7 列 × 6 行网格
- [ ] T012 每格：日期数字（非本月灰显、今天高亮）+ 最多 3 条日程 + 「还有 N 条」
- [ ] T013 点击条目 → 复用既有编辑逻辑；点击格空白 → 打开新建并预填该日期
- [ ] T014 单月实例数超过加载条数时显示「另有 N 条未显示」

## Phase 4: 验证与文档

- [ ] T015 `type-check` / `build` / `test:logic` 全绿
- [ ] T016 真机：造本地 07:00 的日程，列表与月视图都落在正确日期（东八区）
- [ ] T017 真机：切月、回到今天、点空白新建、点条目编辑
- [ ] T018 README 路线图加阶段 13
- [ ] T019 提交：`docs(calendar): 阶段 13 规格` + `feat(calendar): 阶段 13 实现`

## 完成记录

（实现后回填）
