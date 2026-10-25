# Quickstart / 验收清单: 阶段 9 — 可靠提醒

**Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md) | **Contract**: [contracts/reminder-delivery.md](./contracts/reminder-delivery.md)

> ✅ 已实际执行并验证 ｜ ⏳ 未执行（须写原因） ｜ ❌ 失败

## 前置

```bash
cd /Volumes/WD770/Project/personal-ai-os/frontend
npm run test:logic        # 通道决策、去重、汇总、异常降级
npm run type-check && npm run build
```

真机：后端 8080 + 前端 5173 + postgres:55432；Playwright 里用 `context.grantPermissions(['notifications'])`
切换权限状态，并用注入脚本 stub `window.Notification` 以捕获构造调用（浏览器不提供"读取已弹通知"的 API）。

## 自动化验收

| 项 | 动作 | 关联 | 结果 |
|---|---|---|---|
| A-通道矩阵 | `chooseChannel` 4×2 八种组合逐一断言 | 契约一节 | ⏳ |
| A-去重 | 同一 id 连续 3 次 → 通知 1 次 | N-4 / SC-003 | ⏳ |
| A-汇总阈值 | 4 条 → 1 条汇总；3 条 → 3 条单条 | N-5 / N-6 / SC-004 | ⏳ |
| A-汇总后不重发 | 汇总后再返回同样 4 条 → 0 次 | N-7 | ⏳ |
| A-构造异常降级 | `Notification` 抛错 → 不冒泡、转站内提示 | N-8 / FR-010 | ⏳ |
| A-内容契约 | 单条含来源前缀与时间；汇总含数量 | 契约二节 | ⏳ |

## 真机验收

| 项 | 动作 | 结果 |
|---|---|---|
| M-权限 default | 进入日程页：显示「开启浏览器通知」按钮，且**未**弹出权限请求 | ⏳ |
| M-权限 granted | 点按钮授权 → 状态变「通知已开启」，刷新后仍是已开启 | ⏳ |
| M-权限 denied | 拒绝后：显示阻止文案 + 恢复指引，无按钮 | ⏳ |
| M-后台通知 | 页面隐藏 + granted → stub 记录到 1 次构造，参数含标题与时间 | ⏳ |
| M-页面可见降级 | 页面可见 → 0 次构造，站内提示可见 | ⏳ |
| M-不支持 | 删除 `window.Notification` → 显示「不支持」文案 | ⏳ |
