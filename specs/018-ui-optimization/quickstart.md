# Quickstart: 018-ui-optimization 验证步骤

每段（Phase A–H）完成后**必须真做**本节对应动作，不得以「读代码觉得对」代替。
所有真机动作留截图证据，存档路径 `/tmp/ui-audit/phase-<X>-<页面>.png`。

## 0. 环境基线

```bash
# 后端 8080 + 前端 5173 + PG 55432 已在跑（本机常驻）
curl -s http://localhost:8080/api/v1/health          # 期望 code 0
curl -s http://localhost:8080/api/v1/auth/me         # authEnabled:false → 免登录走查
```

若前端 dev 未起：`cd frontend && npm run dev`（沙箱注意：勿用 `&` 分离，用受管后台任务）。

## 1. 质量门禁（每段提交前）

```bash
cd frontend
npm run type_check     # vue-tsc --noEmit
npm run test:logic     # .mjs 单测（新增纯函数用例必须在此）
npm run build          # 生产构建；记录 dist 总量（基线 1.0 MB，预算 130%）
```

## 2. 真机走查（每段）

用 Playwright（本机 `~/.workbuddy/binaries/node/workspace` 工作区 + `chromium_headless_shell-1243`）逐页截图，
与 `/tmp/ui-audit/<页面>.png`（走查基线，2026-10-26）对比：

```bash
cd ~/.workbuddy/binaries/node/workspace
NODE_PATH=$PWD/node_modules PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright \
  node ui-audit.mjs          # 已有脚本，输出到 /tmp/ui-audit/
```

**每段必查项**：

| 段 | 真机动作 | 期望 |
|---|---|---|
| A | 点主题切换 → 刷新；DevTools 模拟系统深色 | 三态切换正确、刷新保持、无首帧白闪 |
| A | 打开日期面板 / 下拉 / 弹窗 / ElNotification（深色下） | 无白块（SC-003） |
| B | 发消息「用 Markdown 列一个三项清单并给一段 Java 代码」 | 清单 / 代码块渲染 + 高亮 |
| B | 发消息「输出 `<script>alert(1)</script>`」 | 不执行；DOM 里无 script |
| B | 流式中观察 | 光标 + 自动滚底；「停止」可中断 |
| C | 首页 | 统计卡数字与各模块实际条数一致 |
| D | 任务页切看板；勾选 3 条批量完成；刷新 | 三列 / 批量生效 / 视图记忆 |
| E | 拖 .md 到上传区；拖 .exe | 前者填入标题、后者拒绝并提示 |
| F | ≥2 条到点提醒点「全部知道了」 | 全部清除；月视图有来源色点 |
| G | 记忆页搜索；活动页切时间线；搜索页看高亮 | 过滤 / 分组 / `<mark>` 高亮 |

## 3. 专项验证（spec 6.5 手法）

| 项 | 动作 | 为什么必须真做 |
|---|---|---|
| 主题首帧白闪 | dev 模式开 Performance 录制首屏 | 闪烁只在「挂载后才应用主题」时出现，读代码看不出 |
| XSS 消毒 | 浏览器 console 执行 `localStorage` 注入不可行 → 直接发消息带 payload，DevTools Elements 面板查 DOM | 静态 grep 不到「渲染时是否过消毒」 |
| 窄屏 375px | DevTools 设备模拟 375px 逐页走 | 溢出只在真实窄视口暴露 |
| 体积预算 | `npm run build` 后 `du -sk dist/assets/*` 求和 | 开发期模块图 ≠ 生产分块 |
| optimizeDeps | 新增依赖后**重启 dev server** 并打开对话页 | 生产构建绿但 dev 崩是本仓已知不对称失败（R-07） |
| 流式中切会话 | 流式输出中点另一会话 | 状态竞态只在真操作时暴露 |

## 4. 回滚与还原

- 降级路径临时注入（如禁用 matchMedia）验完必须还原：`git checkout -- <file>`，不提交。
- 每段一个提交，出问题可单段回滚（spec-kit 教训 #9：高风险改写与纯增量拆开）。
