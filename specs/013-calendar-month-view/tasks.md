---
description: "Task list for 阶段 13 — 日历月视图"
---

# Tasks: 阶段 13 — 日历月视图

**Input**: Design documents from `/specs/013-calendar-month-view/`

---

## Phase 1: 日期纯函数（先修 bug）

- [x] T001 `src/lib/calendar-dates.ts`：`localDayKey(iso)` —— 按本地时区取 `YYYY-MM-DD`
- [x] T002 同文件：`buildMonthCells(cursor, today)` —— 固定 42 格，周一为第一列，跨月 `inMonth=false`
- [x] T003 同文件：`monthRange(cursor)` —— 本月 1 日 00:00 ~ 次月 1 日 00:00（本地）→ ISO
- [x] T004 同文件：`shiftMonth(cursor, delta)` —— 处理 12 月 +1 → 次年 1 月
- [x] T005 `frontend/test/calendar-dates.test.mjs`：时区归属（构造 UTC 串断言本地日期）、
      42 格、跨月灰格、月份边界、闰年 2 月

## Phase 2: store 改造

- [x] T006 `groupedByDay` 改用 `localDayKey`（**修 UTC 分组缺陷**）
- [x] T007 `loadOccurrences` 记录 `windowFrom` / `windowTo`；无参调用复用窗口
- [x] T008 `loadOccurrences` 支持 `sizeOverride`（月视图一次拉 500 条，后端上限）
- [x] T009 `test/calendar-store.test.mjs` 补：无参刷新复用上次窗口（不回到默认窗口）

## Phase 3: 月视图

- [x] T010 `CalendarView` 加 `viewMode: 'list' | 'month'`，默认 `list`
- [x] T011 月视图模板：月份标题 + 上一月/今天/下一月 + 7 列 × 6 行网格
- [x] T012 每格：日期数字（非本月灰显、今天高亮）+ 最多 3 条日程 + 「还有 N 条」
- [x] T013 点击条目 → 复用既有编辑逻辑；点击格空白 → 打开新建并预填该日期
- [x] T014 单月实例数超过加载条数时显示「另有 N 条未显示」

## Phase 4: 验证与文档

- [x] T015 `type-check` / `build` / `test:logic` 全绿
- [x] T016 真机：造本地 07:00 的日程，列表与月视图都落在正确日期（东八区）
- [x] T017 真机：切月、回到今天、点空白新建、点条目编辑
- [x] T018 README 路线图加阶段 13
- [x] T019 提交：`docs(calendar): 阶段 13 规格` + `feat(calendar): 阶段 13 实现`

## 完成记录

**提交**：`5fc7aa7` feat(calendar): 阶段 13 日历月视图（T001-T018）

**测试**
- 前端 `npm run test:logic`：**187** 断言通过（原 155 + 新增 32）
  - `calendar-dates.test.mjs`（26）：本地时区归属、42 格、周一首列、跨月灰格、
    节气边界（12 月 +1 跨年、3 月 31 日 -1 不溢出）、闰年 2 月
  - `calendar-store.test.mjs` 追加（6）：无参刷新复用窗口、本地日期分组、size 覆盖
- `type-check`、`build` 通过；后端零改动（未跑 verify，本阶段不碰后端）

**真机验证（东八区，Playwright）**
- 42 格、表头「一 二 三 四 五 六 日」、今日高亮 1 格、跨月灰格 11 个
- 造一条本地 `2026-10-26 07:00`（UTC 为 `2026-10-25T23:00Z`）→ **落在 26 号格并显示 07:00**
- 「下一月」→ 2026 年 11 月；「今天」→ 回到 10 月
- 点格子「+」→ 提示「已填入新建表单：2026/10/8 09:00:00」，表单日期已预填
- 切回列表 → 27 条，行为与原列表一致；页面 0 错误

**踩坑 / 经验**
- **时区测试不能写死 UTC 字符串**。`'2026-10-26T07:00:00Z'` 在东八区是 15:00、在 UTC 是 07:00，
  断言会随运行机器变化。正确写法是「本地时间构造 → `toISOString()` → 再算回本地」的闭环，
  这样任何时区都成立，而错误实现（截取 UTC）在东八区会立刻失败。
- Element Plus 的 `el-radio-group` 的 `change` 载荷是宽类型，
  `v-model` 绑字面量联合类型会类型不报错但语义含糊；用 `:model-value` + `@change` 收窄更明确。
- 自动注册组件后 `components.d.ts` 会被插件改写（本次新增 ElPopover / ElRadioGroup / ElRadioButton），
  它属于仓库跟踪文件，要一起提交。
