# Contract: 提醒送达（通道 / 权限 / 去重）

**Date**: 2026-10-25 | **Spec**: [spec.md](../spec.md)

## 一、通道决策（纯函数，顺序即契约）

```
chooseChannel(permission, pageVisible)
  1. pageVisible            → 'inline'   （无论权限如何：用户就在屏幕前）
  2. permission === 'granted' → 'browser'
  3. 其余                    → 'none'    （徽标兜底，不冒充送达）
```

| permission \ pageVisible | true | false |
|---|---|---|
| `granted` | `inline` | `browser` |
| `default` | `inline` | `none` |
| `denied` | `inline` | `none` |
| `unsupported` | `inline` | `none` |

> 注意：`inline` 在权限为 `default/denied` 时**仍然使用** —— 页面可见时用站内提示是"看得见的提示"，
> 不涉及任何权限。这与「页面不可见时不冒充送达」并不矛盾。

## 二、通知内容契约

单条（≤3 条时）：

| 字段 | 值 |
|---|---|
| `title` | 提醒标题（任务/日程标题），前缀来源：`[任务]` / `[日程]` |
| `body` | 到点时间（本地化，`HH:mm`）+ 若有描述则追加一行 |
| `tag` | 提醒 id —— 让浏览器把同一提醒的重复通知合并而不是堆叠 |
| `silent` | `false` |

汇总（>3 条时，一条）：

| 字段 | 值 |
|---|---|
| `title` | `有 N 条提醒到点` |
| `body` | 前 3 条的标题以「、」连接 + `等` |
| `tag` | `reminder-summary` |

点击行为：`window.focus()` + 导航到 `/calendar`。

## 三、权限状态机

| 当前状态 | 界面 | 点击行为 |
|---|---|---|
| `unsupported` | 文案「当前浏览器不支持通知，提醒仅在页面内显示」 | 无按钮 |
| `default` | 按钮「开启浏览器通知」 | 调用 `requestPermission()`，结果写回状态 |
| `granted` | 标签「通知已开启」 | 无按钮（不需要） |
| `denied` | 文案「浏览器已阻止通知」+ 指引「在浏览器的站点设置中允许通知后刷新页面」 | 无按钮（R-05） |

**请求时机**：只在点击时。页面加载、进入日程页、轮询开始时**都不得**调用 `requestPermission`。

## 四、去重与汇总

| 规则 | 值 |
|---|---|
| 去重键 | 提醒 id |
| 去重范围 | `ReminderNotifier` 实例生命周期（= 页面会话） |
| 已通知后再次返回 | 跳过，不重复计数 |
| 汇总阈值 | 新提醒 > 3 条 → 一条汇总 |
| 汇总后是否记录各自 id | **是** —— 否则下一次轮询会把它们当成"新的"再发一遍 |

## 五、行为用例表

| # | Given | When | Then |
|---|---|---|---|
| N-1 | 权限 granted、页面隐藏 | 轮询返回 1 条 | 构造 1 次 `Notification`，参数符合内容契约 |
| N-2 | 权限 granted、页面可见 | 轮询返回 1 条 | 0 次 `Notification`，1 次站内提示 |
| N-3 | 权限 default、页面隐藏 | 轮询返回 1 条 | 0 次通知、0 次站内提示（仅徽标） |
| N-4 | 同一条提醒连续 3 次轮询 | — | 只通知 1 次 |
| N-5 | 一次返回 4 条 | — | 1 次汇总通知，标题含「4」 |
| N-6 | 一次返回 3 条 | — | 3 次单条通知 |
| N-7 | 汇总之后再次返回同样 4 条 | — | 0 次新通知（id 已记录） |
| N-8 | `Notification` 构造抛异常 | 轮询返回 1 条 | 不抛出、降级为站内提示、后续轮询继续工作 |
| N-9 | 权限 denied | 进入日程页 | 显示阻止文案与指引，无按钮，**不调用** `requestPermission` |
| N-10 | 权限 default | 点击按钮 → 浏览器授权 | 状态变 granted，按钮消失 |
| N-11 | 无 `window.Notification` | 进入日程页 | 显示「不支持」文案，无按钮 |
