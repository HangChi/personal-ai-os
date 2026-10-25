# Data Model: 阶段 9 — 可靠提醒

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

## 后端：零变更

不新增表、不新增列、不新增迁移。提醒的物化、唯一约束去重、已读状态在阶段 4 已完成
（`reminder` 表 + `(source_type, source_id, occurrence_at)` 唯一约束）。

本阶段**只**消费既有 `GET /api/v1/reminders?dueOnly=true` 的返回。

## 前端新增的运行时状态（不持久化）

| 状态 | 位置 | 生命周期 | 说明 |
|---|---|---|---|
| `permission` | `calendar` store | 页面会话；初始从 `Notification.permission` 读取 | `granted / default / denied / unsupported` |
| `notifiedIds` | `ReminderNotifier` 实例 | 页面会话（刷新即重置） | 已发过通知的提醒 id，防止每 30 秒重复打扰 |
| `requesting` | `calendar` store | 请求权限期间 | 按钮 loading，避免连点 |

### 为什么都不持久化

见 research R-03：持久化去重会让「用户没点已读」的提醒在刷新后永远静默。
权限状态本身的**权威来源是浏览器**（`Notification.permission`），
每次进入页面重新读取即可，本地存一份只会产生「本地说 granted、浏览器说 denied」的分歧。

## 明确不做的变更

| 不做的变更 | 理由 |
|---|---|
| 新增 `notification_log` 表 | 单用户本地场景无需追溯"推送过什么"；去重范围是页面会话 |
| 提醒表加 `notified_at` 列 | 与「已读」语义重叠，且会让"未读但不再提醒"变成可能 —— 正是要避免的 |
| 后端推送通道（SSE/WebSocket） | 页面关闭时同样无效（spec 明确不含） |
