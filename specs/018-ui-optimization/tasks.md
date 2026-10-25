# Tasks: 018-ui-optimization

> 按 Phase 分组，**每个 Phase 一次提交**。T 编号全局唯一。
> 硬约束（贯穿全清单，Notes 见文末）。

## Phase A — 设计系统与全局框架

- [ ] **T001** 新增 `marked` / `highlight.js` / `dompurify` 依赖，并同步 `vite.config.ts` `optimizeDeps.include`（R-07）；改完 `rm -rf node_modules/.vite`
- [ ] **T002** `src/styles/tokens.css`：`--os-space-1..6` / `--os-radius-sm..lg` / `--os-shadow-card|card-hover|popup` / `--os-font-xs|sm|md` + 注释说明每个令牌替代了哪些散落值
- [ ] **T003** 暗色落地：`element-plus/theme-chalk/dark/css-vars.css` 按需引入（`html.dark`）+ `color-scheme` 随主题 + 收编现有 4 处硬编码 hex（App.vue / main.css / LoginView.vue）
- [ ] **T004** `src/lib/theme.ts`：`resolveTheme(pref, systemDark)` / `applyTheme(resolved)`（纯函数部分）
- [ ] **T005** `src/stores/ui.ts`：UiStore 三态主题 + matchMedia 监听 + localStorage `os.theme`
- [ ] **T006** `main.ts` 挂载前同步应用主题（禁 onMounted 里应用——C-02 反模式）；`index.html` 内联引导脚本兜底首帧
- [ ] **T007** 公共组件 5 件：`PageHeader` / `FilterBar` / `EmptyState` / `AppPagination` / `StatusTag`（契约 C-03，`src/components/` 自动注册）
- [ ] **T008** `App.vue` 顶栏重构：图标导航（EP icon 按需）、搜索框加宽 + `Ctrl/Cmd+K` 聚焦、ThemeToggle 挂载、**移除「阶段 17」硬编码**、页脚精简
- [ ] **T009** `frontend/test/theme.test.mjs`：`resolveTheme` 全分支（三态 × 系统明暗）+ relative-time 边界（59s/60s/23h/24h/7d）
- [ ] **T010** 走查：主题三态真机切换 + 浮层暗色检查（日期面板/下拉/弹窗/通知）+ `type_check` / `test:logic` / `build` 全绿；截图存档
- [ ] **T011**（可并行）favicon：`index.html` + `public/favicon.svg`（简单 SVG，AI 字母标）

## Phase B — 对话页深度优化

- [ ] **T012** `MarkdownRenderer.vue`：marked → DOMPurify → v-html；highlight.js 核心 + 10 语言按需注册；主题联动代码高亮 CSS；复制按钮；流式光标（C-01）
- [ ] **T013** `ChatView.vue` 消息气泡：MarkdownRenderer 接入（用户 + 助手）、头像、时间（`relative-time`）、复制按钮
- [ ] **T014** `ChatView.vue` 工具调用轨迹：消息下方可折叠「工具调用」区（工具名 + 结果摘要）；**数据以现有消息字段为限，拿不到的字段降级并在完成记录说明**
- [ ] **T015** `src/lib/session-group.ts` + `frontend/test/session-group.test.mjs`：今天/昨天/更早分组（保持输入顺序、空组不渲染）
- [ ] **T016** `ChatView.vue` 会话列表：分组渲染 + 相对时间副标题
- [ ] **T017** 走查：Markdown 四类语法渲染抽查 + `<script>` payload 不执行 + 流式光标/停止 + `test:logic` / `type_check` / `build` 全绿；截图存档

## Phase C — 总览页 Dashboard 化

- [ ] **T018** `HomeView.vue` 统计卡行：待办 / 逾期 / 今日日程（或即将到来）/ 记忆数 / 文档数；单卡失败降级「—」（FR-009）
- [ ] **T019** `HomeView.vue` 能力卡片网格：图标 + 标题 + 一句说明 + 点击跳转（替换纯文字 bullet list）
- [ ] **T020** 走查：统计数字与模块实际一致 + 点击跳转 + 构建全绿；截图存档

## Phase D — 任务页（表格重构 + 看板 + 批量）

- [ ] **T021** `src/lib/board.ts` + `frontend/test/board.test.mjs`：`groupTasksByStatus`（三列、列内保序）
- [ ] **T022** `TasksView.vue`：PageHeader/FilterBar/AppPagination/StatusTag 套用；mini 统计卡；表格默认按截止升序（无截止排最后）
- [ ] **T023** `TasksView.vue` 看板视图：三列 + 列头计数 + 空列空态 + 卡片操作按钮（完成/编辑/删除，**无拖拽**）；视图模式持久化 `os.tasks.view`
- [ ] **T024** `TasksView.vue` 批量操作：表格多选 + 批量完成/删除（`Promise.allSettled` 汇总报告，C-05 失败语义）
- [ ] **T025** 走查：双视图切换/记忆、批量成功与部分失败、构建全绿；截图存档

## Phase E — 知识库页（Tab 化 + 拖拽上传）

- [ ] **T026** `KnowledgeView.vue`：`el-tabs` 三 Tab（文档/检索/问答），偏好持久化 `os.knowledge.tab`
- [ ] **T027** `KnowledgeView.vue` 拖拽上传：dragover 高亮 / drop 复用现有校验 / 拒绝提示（C-06）
- [ ] **T028** `KnowledgeView.vue` 表格：PageHeader/AppPagination 套用 + 片段数/字符数列头 tooltip
- [ ] **T029** 走查：Tab 切换、拖入支持/不支持文件、构建全绿；截图存档

## Phase F — 日程页四项

- [ ] **T030** `CalendarView.vue` 提醒批量确认：「全部知道了」`Promise.allSettled` 逐条 acknowledge（C-05 同款语义）
- [ ] **T031** `CalendarView.vue` 新建表单分组：基本信息 / 时间 / 重复与提醒三组，视觉分隔
- [ ] **T032** `CalendarView.vue` 月视图来源色点（日程=主色 / 任务=warning）+ 通知横幅 granted 时折叠为一行
- [ ] **T033** 走查：批量确认、表单分组、色点、构建全绿；截图存档

## Phase G — 四小页收尾

- [ ] **T034** `src/lib/highlight-text.ts` + `frontend/test/highlight-text.test.mjs`：`highlightSegments`（空串/特殊字符/多词/重叠，C-07）
- [ ] **T035** `MemoryView.vue`：内容搜索（纯前端过滤）+ EmptyState 套用
- [ ] **T036** `ActivityView.vue`：时间线视图选项（按天分组）+ 会话 ID 截断 8 位 + tooltip
- [ ] **T037** `SearchView.vue`：关键词 `<mark>` 高亮（C-07）+ skeleton 加载态
- [ ] **T038** `LoginView.vue`：品牌区（Logo + 名称 + 一句说明），与全站令牌一致
- [ ] **T039** 走查：四页各自能力 + 构建全绿；截图存档

## Phase H — 收尾

- [ ] **T040** 全站 8 页 + 375px 窄屏 + 深色浮层终检；截图对比基线存档
- [ ] **T041** `npm run type_check` / `test:logic` / `build` 终跑 + `du -sk dist/assets/*` 体积记录（对 SC-001 130% 预算）
- [ ] **T042** README 状态行更新（前端小节）；`.workbuddy/memory` 当日 log + MEMORY.md 回填（含 R-07 optimizeDeps 新依赖教训）

## Notes（贯穿全清单的硬约束）

1. **零后端改动**：任何「要不要加个接口」的念头都回到 spec Out of Scope；批量操作一律 `Promise.allSettled` 逐条现有接口。
2. **消毒顺序不可换**：marked 解析 → DOMPurify → v-html；漏一步即 XSS（C-01）。
3. **主题必须挂载前应用**：`main.ts` 同步 + index.html 内联兜底；onMounted 里应用 = 首帧白闪（C-02 反模式）。
4. **新增依赖必须同批进 `optimizeDeps.include`**，并 `rm -rf node_modules/.vite` 再重启 dev（R-07）。
5. **公共组件只放 `src/components/`**，靠目录约定自动注册；不全局注册、不手动 import（R-08）。
6. **每段提交前 quickstart §1 三门禁全绿 + §2 真机动作留截图**；未验项保持未勾选并写明原因（宪法 V）。
7. **失败就地提示**：弹窗内的保存失败用 ElMessage.error，不让用户对着无响应的按钮（沿袭 TasksView/CalendarView 既有注释的教训）。

## 完成记录（实现时回填）

<!-- 每 Phase 完成后在此追加：提交 hash、实测数字、与规格的偏差及原因 -->
