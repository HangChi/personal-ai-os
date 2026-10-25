# Implementation Plan: 阶段 18 — 全站 UI 优化

**Branch**: `018-ui-optimization` | **Date**: 2026-10-26 | **Spec**: [spec.md](./spec.md)

## Summary

纯前端视觉与体验重构：先建设计令牌 + 深色模式 + 公共组件（Phase A），再逐页套用（B–G），
对话页 Markdown 渲染是感知最强的单点（Phase B）。

**零后端改动、零迁移；新增依赖白名单 3 个**（marked / highlight.js / dompurify，合计 gzip ~70 KB）。
决策与依据见 [research.md](./research.md)（R-01 ~ R-12），前端模块契约见 [contracts/ui-modules.md](./contracts/ui-modules.md)。

## Technical Context

- Vue 3.5 + TS 5.9 + Vite 8 + Pinia 4 + Element Plus 2（按需引入已落地）
- 新增：marked 15.x / highlight.js 11.x / dompurify 3.x（**必须同步进 `optimizeDeps.include`**，R-07）
- 测试：`npm run test:logic`（`.mjs`）+ type_check + build；真机 Playwright 截图

## Constitution Check

| 原则 | 判定 | 依据 |
|---|---|---|
| I. 简单优先 | ✅ | 依赖仅 3 个且逐个有 Decision + Alternatives（R-01/02/03）；不做拖拽看板 / 虚拟滚动 / Storybook（Out of Scope） |
| II. 边界即安全 | ✅ | DOMPurify 消毒封住「模型输出 → DOM」新攻击面（R-03）；无密钥 |
| III. 分层与写路径 | ✅ | 纯前端；写路径零改动；公共组件走 unplugin-vue-components 目录约定（R-08） |
| IV. 数据变更可追溯 | ✅ | 零 Flyway 脚本（data-model.md 声明） |
| V. 可验证的交付 | ✅ | 每段 type_check + test:logic + build 全绿 + quickstart 真机动作留截图；未验项不勾完成 |
| 技术栈约束 | ✅ | 三依赖均取 npm 稳定主线；TS 不动（~5.9.3） |
| 工作流：阶段串行 | ✅ | 018 接 017；每 Phase 一次提交 |

## Complexity Tracking

无违例登记。曾评估后**否决**的更「重」方案：Tailwind / CSS-in-JS（R-05）、
pinia-plugin-persistedstate（R-10）、dayjs（R-09）——理由均在 research.md。

## Project Structure

**前端新增**

```
src/styles/tokens.css          设计令牌（--os-*：间距/圆角/阴影/字体节奏）
src/styles/dark.css            html.dark 下引入 EP 暗色 css-vars + color-scheme
src/stores/ui.ts               UiStore（主题三态 + 视图偏好）
src/lib/theme.ts               resolveTheme / applyTheme
src/lib/relative-time.ts       relativeTime(iso, now)
src/lib/session-group.ts       groupSessionsByDay
src/lib/highlight-text.ts      highlightSegments
src/lib/board.ts               groupTasksByStatus
src/components/PageHeader.vue  FilterBar.vue  EmptyState.vue  AppPagination.vue
src/components/StatusTag.vue   MarkdownRenderer.vue  ThemeToggle.vue
src/main.ts                    改：挂载前同步应用主题（C-02 反模式）
frontend/test/theme.test.mjs   relative-time / session-group / highlight-text / board 用例
```

**前端修改（按 Phase）**

```
Phase A  App.vue（顶栏：图标导航/加宽搜索/Ctrl+K/主题切换/去阶段标识）
         index.html（favicon + 内联主题引导脚本）
         styles/main.css（token 引入 + 反模式修复）
Phase B  views/ChatView.vue（MarkdownRenderer/头像/时间/复制/流式光标/工具轨迹）
         stores/chat.ts（相对时间数据透出，按需）
Phase C  views/HomeView.vue（统计卡 + 能力卡片）
Phase D  views/TasksView.vue（统计卡/表格重构/看板/批量）
Phase E  views/KnowledgeView.vue（Tab 化 + 拖拽上传）
Phase F  views/CalendarView.vue（提醒批量确认/表单分组/月视图着色/横幅折叠）
Phase G  views/MemoryView.vue（搜索） views/ActivityView.vue（时间线/ID 截断）
         views/SearchView.vue（高亮） views/LoginView.vue（品牌化）
Phase H  全站走查 + README 状态更新
```

## Phase 拆分与提交边界

| Phase | 内容 | 性质 | 提交 |
|---|---|---|---|
| A | 令牌 + 暗色 + 公共组件 + 顶栏 | 纯增量（新文件为主，App.vue 改） | `feat(ui):` |
| B | 对话页（Markdown / 气泡 / 轨迹） | 纯增量组件 + ChatView 改造 | `feat(ui):` |
| C | 总览 Dashboard | 单页重写 | `feat(ui):` |
| D | 任务页（看板 + 批量） | 单页重写 | `feat(ui):` |
| E | 知识库 Tab + 拖拽 | 单页重写 | `feat(ui):` |
| F | 日程页四项 | 单页重写 | `feat(ui):` |
| G | 四小页收尾 | 四页小改 | `feat(ui):` |
| H | 走查 + 文档 | docs | `docs(ui):` |

每个 Phase 的验收回写进 [tasks.md](./tasks.md) 的完成记录段。

## 风险与降级

| 风险 | 信号 | 降级 |
|---|---|---|
| 体积超 130% 预算 | build 后 du 超 1.3 MB | highlight 语言 10→6；marked 直引 min 版（R-汇总） |
| marked 流式每帧重渲染卡顿 | 长回复掉帧 | 节流到 rAF 合帧；仍卡则截断预览 + 展开（C-01 用例 7） |
| EP 暗色浮层残留白块 | 走查截图 | 逐组件补 `dark:` 覆盖；不得改 EP 源码 |
| dev server 崩（optimizeDeps） | 重启后白屏 / CJS 报错 | 按 R-07 加 include + `rm -rf node_modules/.vite` |
| 深色首帧白闪 | Performance 录制 | 引导脚本前移 index.html（C-02） |
