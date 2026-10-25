---
description: "Task list for 阶段 9 — 可靠提醒"
---

# Tasks: 阶段 9 — 可靠提醒

**Input**: Design documents from `/specs/009-reliable-reminders/`

> 每个 Phase 一次提交；完成后勾选。后端零改动。

## Phase 1: 交付核心（纯逻辑）

- [ ] T001 `notifications/permission.ts`：`readPermission()`（含 `unsupported`）、`requestPermission()`
- [ ] T002 `notifications/reminder-notifier.ts`：`chooseChannel` 纯函数（4×2 矩阵）
- [ ] T003 `ReminderNotifier`：去重（Set）、汇总阈值 3、`notify` 注入（默认 `new Notification`）、构造异常降级
- [ ] T004 `test/reminder-notifier.test.mjs`：N-4/N-5/N-6/N-7/N-8 + 通道矩阵
- [ ] T005 **Phase 1 提交**

## Phase 2: 接入页面

- [ ] T006 `stores/calendar.ts`：权限状态、`enableNotifications()`、轮询后交给 notifier
- [ ] T007 `views/CalendarView.vue`：通知状态条（4 种状态）+ 站内提示（`ElNotification`）
- [ ] T008 **Phase 2 提交**

## Phase 3: 真机验证与文档

- [ ] T009 Playwright：权限三态 + 后台通知（stub 捕获）+ 可见降级 + 不支持降级
- [ ] T010 回填 quickstart；README/architecture 补阶段 9
- [ ] T011 **提交**

## Notes

- **页面不可见且无权限时不要用站内提示冒充送达**（R-02）—— 那是虚假安全感。
- 汇总之后必须把 4 个 id 都记入去重集合，否则下一轮会重发（N-7）。
- `denied` 之后不再调用 `requestPermission`（浏览器不会弹窗，按钮点了没反应属于假交互）。
- 本阶段不碰后端；若出现 `.sql` 或 Controller 改动，说明走偏了。
