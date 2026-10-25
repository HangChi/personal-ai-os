# Implementation Plan: 阶段 9 — 可靠提醒

**Branch**: `009-reliable-reminders` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## Summary

纯前端阶段。把「到点提醒」从**页面内徽标**扩展为**送达通道**：权限已授予且页面不在前台时发系统通知，
页面可见时用站内提示，权限不可用时明确告知限制。不动后端、不加依赖。

## Technical Context

- **前端**：Vue 3 + TypeScript + Pinia + Element Plus；浏览器 `Notification` API
- **后端**：**不改动**（提醒物化/去重/已读在阶段 4 已完成）
- **新增依赖**：无
- **规模**：2 个新模块（权限读取、通知器）+ store 集成 + 日程页状态条 + 测试

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I 简单优先 | ✅ | 零新依赖；不引 Service Worker / Web Push；去重用内存 Set 而非持久化 |
| II 边界即安全 | ✅ | 不涉及数据写路径；通知只读已存在的提醒；权限请求必须由用户手势触发，不劫持 |
| III 分层与写路径收敛 | ✅ | 交付逻辑放独立模块（`src/notifications`），store 只编排；不把 DOM/通知 API 塞进视图 |
| IV 数据可追溯 | ✅ | 无数据库变更 |
| V 可验证的交付 | ✅ | 通道决策是**纯函数**（单测）；通知构造用注入的 `notify` 函数以便断言；浏览器侧用 stub `Notification` 验证真实调用 |

**结论**：无违例。Complexity Tracking：无（本阶段没有需要论证的额外抽象）。

## Project Structure

```text
frontend/src/
├── notifications/
│   ├── permission.ts            # 读取 / 请求权限；封装「不存在 Notification」的情况
│   └── reminder-notifier.ts     # chooseChannel（纯函数）+ ReminderNotifier（去重、汇总）
├── stores/calendar.ts           # 改：轮询后交给 notifier；新增权限状态与 enableNotifications()
└── views/CalendarView.vue       # 改：通知状态条（按钮 / 已开启 / 已阻止+指引 / 不支持）

frontend/test/
└── reminder-notifier.test.mjs   # 通道决策、去重、汇总、异常降级
```

## Phase 0 / Phase 1 结论

- **Phase 0**：见 [research.md](./research.md)，5 条决策（通道选择、去重范围、汇总阈值、权限请求时机、失败处理）。
- **Phase 1**：无数据结构变更（[data-model.md](./data-model.md)）；交付契约见
  [contracts/reminder-delivery.md](./contracts/reminder-delivery.md)。
