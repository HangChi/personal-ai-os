---
description: "Task list for 阶段 9 — 可靠提醒"
---

# Tasks: 阶段 9 — 可靠提醒

**Input**: Design documents from `/specs/009-reliable-reminders/`

> 状态：**已实现并验证**（2026-10-25）。验收证据见 [quickstart.md](./quickstart.md)。后端零改动。

## Phase 1: 交付核心（纯逻辑）✅

- [x] T001 `notifications/permission.ts`：`readPermission()`（含 `unsupported`）、`requestPermission()`（`denied` 时不再调用浏览器 API）
- [x] T002 `notifications/reminder-notifier.ts`：`chooseChannel` 纯函数（4×2 矩阵）
- [x] T003 `ReminderNotifier`：去重 Set、汇总阈值 3、`notify` 注入、构造异常降级 + 记账后发送
- [x] T004 `test/reminder-notifier.test.mjs`：8 组通道 + 去重/汇总/降级/自愈/点击共 43 条断言

## Phase 2: 接入页面 ✅

- [x] T006 `stores/calendar.ts`：`notificationPermission`、`requestingPermission`、`inlineNotices`、`enableNotifications()`、`deliverReminders()`
- [x] T007 `views/CalendarView.vue`：通知状态条（4 态）+ `ElNotification` 站内提示
- [x] T008 **修复递归更新**：`consumeInlineNotices()` 空数组不重写 + 去掉 watch 的 `deep`

## Phase 3: 真机验证与文档 ✅

- [x] T009 Playwright 三场景（default / granted+hidden / denied）+ 点击授权场景，控制台 0 错误
- [x] T010 回填 quickstart；README 与 architecture 路线图补阶段 9
- [x] T011 **提交**

## 完成记录

| 内容 | 结果 |
|---|---|
| 前端基线 | 81 → **124** 断言全通过；`type-check` 0 退出；构建成功 |
| 后端 | **零改动**（无迁移、无 Controller/Service 变更） |
| 真机 | 4 个场景（含真实提醒数据）+ 控制台 0 错误 |
| 关键 bug | 站内提示的 watch 自反馈导致整页渲染中断 —— 单测与类型检查都发现不了，只有真机渲染能暴露 |
| 未验证 | 浏览器层「点击系统通知」：自动化无法触发，逻辑已由单测覆盖 |

## Notes

- 页面不可见且无权限时**不要**用站内提示冒充送达（R-02）—— 虚假安全感比不提醒更糟。
- 汇总之后必须把全部 id 记入去重集合，否则下一轮会重发（N-7）。
- `denied` 之后不再调用 `requestPermission`（浏览器不会弹窗，留个点了没反应的按钮是假交互）。
- watch 一个「回调里会被写回」的状态时，写回动作必须幂等（空值不重写），否则会撞 Vue 的递归更新上限。
