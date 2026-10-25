# Implementation Plan: 阶段 13 — 日历月视图

**Branch**: `013-calendar-month-view` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

日程页加「列表 / 月」视图切换。月视图复用现有的 occurrences 查询（区间=整月），
前端渲染 7×6 网格。**不改后端、不改数据库、不加依赖。**

同时修一个既有缺陷：列表的按天分组取的是 UTC 日期（`startsAt.slice(0,10)`），
东八区下 08:00 前的日程会被排到前一天。

## Technical Context

- **前端**：Vue 3 + TypeScript + Pinia + Element Plus
- **后端**：**不改**
- **测试**：`frontend/test/*.mjs`（Node 直跑 TS，经 `test/loader.mjs` 做类型剥离）
- **规模**：1 个日期工具模块 + store 三处改动 + CalendarView 加一个视图分支 + 1 个测试文件

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 零依赖零迁移。**不**做周视图（R-05）、**不**做拖拽（R-06）、**不**引入日历库（后端已把重复规则展开，前端只需格子） |
| II. 边界即安全 | ✅ | 只读 + 复用既有写入路径；不改任何副作用逻辑 |
| III. 分层与写路径收敛 | ✅ | 月视图的新建/编辑调用的是既有 store action，不新增写路径 |
| IV. 数据变更可追溯 | ✅ | 无结构变更 |
| V. 可验证的交付 | ✅ | 三条纯逻辑有断言（本地时区分组 / 42 格生成 / 月份区间）；真机验证跨时区归属与切月 |
| 工作流：阶段串行 | ✅ | 013 接在 012 之后 |

## Project Structure

```
frontend/src/lib/calendar-dates.ts   新：localDayKey / buildMonthCells / monthRange / groupByLocalDay
frontend/src/stores/calendar.ts      改：分组改本地时区；记住查询窗口；size 可覆盖
frontend/src/views/CalendarView.vue  改：视图切换 + 月网格 + 月份导航
frontend/test/calendar-dates.test.mjs 新
```

## Phase 0 / Phase 1 结论

### R-01 后端一行都不动

后端已经返回**展开后的发生实例**并支持任意区间查询（`from`/`to`）。
月视图需要的只是「换一个区间」——加接口或加 `groupBy=month` 参数，
只会让同一个查询有两种形状，而前端本来就要自己算格子。

### R-02 日期分组必须按本地时区

`startsAt` 是 UTC ISO 串。`slice(0, 10)` 取的是 UTC 日期，
在东八区意味着本地 00:00–07:59 的日程全被算到前一天。

正确做法是 `new Date(iso)` 后用 `getFullYear/getMonth/getDate`（三者都是本地时区）。
这条改成纯函数 `localDayKey()` 并单测 —— 它是本阶段唯一「改错会静默错一整格」的地方。

### R-03 store 要记住查询窗口

现有 `loadOccurrences(from, to)` 不保存窗口，后续 `loadOccurrences()`（增删改后的刷新）
用的是后端默认窗口（今天 + 7 天）。月视图翻到 11 月后新建一条，刷新会跳回本月 —— 数据与网格对不上。

改为把 `windowFrom` / `windowTo` 存进 store，无参调用时复用。

### R-04 固定 42 格

不同月份需要 4~6 行。行数随月份变化会让整页高度在切月时跳动，
连带把下方的列表/按钮顶走。固定 6 行（42 格）用灰格填满，代价是偶尔多一行空白。

### R-05 不做周视图

月视图解决「看全局」，列表解决「看细节」。周视图夹在中间：
信息密度与列表重叠（都是一条条列），视野又不如月视图。
加它只是多一个渲染分支与一套导航状态，收益不明显。

### R-06 不做拖拽改期

拖拽需要：PATCH 时间 → 重复日程「改这一次 / 改全部」的语义选择 → 冲突与失败回滚。
这三件事每一件都比整个月视图复杂，塞进来会把两个都做糙。

## Complexity Tracking

无违例。唯一的结构新增是 `src/lib/`（纯函数模块），目的是让日期逻辑可被 Node 测试直接加载。
