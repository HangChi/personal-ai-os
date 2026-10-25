# Quickstart / 验收清单: 阶段 9 — 可靠提醒

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/reminder-delivery.md](./contracts/reminder-delivery.md)

> ✅ 已实际执行并验证 ｜ ⏳ 未执行（须写原因） ｜ ❌ 失败

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os/frontend
npm run test:logic            # 通道决策、去重、汇总、异常降级
npm run type-check && npm run build
```

**基线**：前端 **124 断言全通过**（阶段 8 的 81 → 124，新增 43）；`type-check` 退出码 0；生产构建成功。

真机：后端 8080 + 前端 5173 + postgres:55432。浏览器侧用 Playwright 注入脚本替换 `window.Notification`
与 `document.visibilityState`，从而**可控地**制造三种权限与两种可见性，并捕获每一次通知构造
（浏览器不提供"读取已弹通知"的 API）。

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-通道矩阵 | `reminder-notifier.test.mjs`：4 权限 × 2 可见性共 8 组断言 | 契约一节 | ✅ |
| A-单条内容 | 标题 `[任务] 交周报`、正文含「到点」、`tag` 用提醒 id | 契约二节 | ✅ |
| A-去重 | 同一 id 连续 3 次 → 只通知 1 次，后两轮 `skipped=1` | N-4 / SC-003 | ✅ |
| A-汇总阈值 | 4 条 → 1 条「有 4 条提醒到点」；3 条 → 3 条单条 | N-5 / N-6 / SC-004 | ✅ |
| A-汇总后不重发 | 同样的 4 条再来一次 → 0 次新通知，`skipped=4` | N-7 | ✅ |
| A-构造异常降级 | `notify` 抛错 → 返回 `channel='inline'` 且把内容交给调用方，不冒泡 | N-8 / FR-010 | ✅ |
| A-异常后自愈 | 第一次失败、第二次恢复 → 第二轮正常走系统通知 | FR-010 | ✅ |
| A-点击行为 | FakeNotification 实例的 `onclick()` → `window.focus()` 与导航各调用 1 次 | FR-005 | ✅ |

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-权限 default | 注入 `permission='default'`：显示「通知未开启」+「开启浏览器通知」按钮 | ✅ |
| M-不自动请求 | 页面加载后 `requestPermission` 调用次数 = **0** | ✅ FR-006 |
| M-点击授权 | 点击按钮 → 状态变「通知已开启」、按钮消失、`requestPermission` 恰好 1 次 | ✅ |
| M-可见 + 未授权 | 页面可见：**0 次**系统通知，站内提示出现（标题「有 8 条提醒到点」） | ✅ SC-002 |
| M-后台 + 已授权 | `visibilityState='hidden'` + `granted`：**1 次**系统通知，标题含数量、正文含前三条标题 | ✅ SC-001 |
| M-已阻止 | `permission='denied'`：显示「通知被阻止」+ 地址栏恢复指引、**0 个按钮**、**0 次**通知 | ✅ SC-005 / SC-006 |
| M-真实数据 | 用真实后端数据（`POST /calendar/events` 造一条即刻到点的日程 + 手动扫描）验证，提醒卡片与提示都出现 | ✅ |
| M-控制台 | 三种场景全程 **0 条 console error** | ✅ |
| M-点击系统通知 | 浏览器层未验：自动化无法触发系统通知的点击。已由单测覆盖 `onclick` 的赋值与实际行为 | ⏳ 说明见上 |

## 实现偏差（同轮已回改代码与文档）

| 偏差 | 处理 |
|---|---|
| **首轮真机验证时整个日程页渲染中断**：提醒卡片消失、`Maximum recursive updates exceeded in component <CalendarView>` | 根因是 `consumeInlineNotices()` **无条件**把数组替换成新的空数组，而视图正在 watch 这个状态 —— 回调改自身依赖，形成自反馈。修法：空数组时直接返回、不重写；同时去掉 watch 的 `deep`（数组整体替换本就触发）。**这类 bug 单测与 type-check 都发现不了，只有真机渲染才暴露** |
| 首轮 A 场景用了真实 `Notification`，而 headless Chromium 默认 `permission='denied'`，拿不到 `default` | 三种权限全部改用注入桩，使状态可控；并在脚本注释里写明原因，避免下次误以为是产品问题 |
| 首轮等待 1200ms 不足（Vite 冷编译） | 改为先 `waitForSelector('.calendar__notify')` 再等待，消除时序脆弱 |

## 已知限制（必须在界面上告诉用户）

- **应用完全关闭时收不到提醒**：本阶段只做页面级送达（浏览器通知），没有 Service Worker / Web Push。
  界面文案已明确写出「应用完全关闭时收不到」，不制造虚假安全感。
