# Tasks: 018-ui-optimization

> 按 Phase 分组，**每个 Phase 一次提交**。T 编号全局唯一。
> 硬约束（贯穿全清单，Notes 见文末）。

## Phase A — 设计系统与全局框架

- [x] **T001** 新增 `marked` / `highlight.js` / `dompurify` 依赖，并同步 `vite.config.ts` `optimizeDeps.include`（R-07）；改完 `rm -rf node_modules/.vite`
- [x] **T002** `src/styles/tokens.css`：`--os-space-1..6` / `--os-radius-sm..lg` / `--os-shadow-card|card-hover|popup` / `--os-font-xs|sm|md` + 注释说明每个令牌替代了哪些散落值
- [x] **T003** 暗色落地：`element-plus/theme-chalk/dark/css-vars.css` 按需引入（`html.dark`）+ `color-scheme` 随主题 + 收编现有 4 处硬编码 hex（App.vue / main.css / LoginView.vue）
- [x] **T004** `src/lib/theme.ts`：`resolveTheme(pref, systemDark)` / `applyTheme(resolved)`（纯函数部分）
- [x] **T005** `src/stores/ui.ts`：UiStore 三态主题 + matchMedia 监听 + localStorage `os.theme`
- [x] **T006** `main.ts` 挂载前同步应用主题（禁 onMounted 里应用——C-02 反模式）；`index.html` 内联引导脚本兜底首帧
- [x] **T007** 公共组件 5 件：`PageHeader` / `FilterBar` / `EmptyState` / `AppPagination` / `StatusTag`（契约 C-03，`src/components/` 自动注册）
- [x] **T008** `App.vue` 顶栏重构：图标导航（EP icon 按需）、搜索框加宽 + `Ctrl/Cmd+K` 聚焦、ThemeToggle 挂载、**移除「阶段 17」硬编码**、页脚精简
- [x] **T009** `frontend/test/theme.test.mjs`：`resolveTheme` 全分支（三态 × 系统明暗）+ relative-time 边界（59s/60s/23h/24h/7d）
- [x] **T010** 走查：主题三态真机切换 + 浮层暗色检查（日期面板/下拉/弹窗/通知）+ `type_check` / `test:logic` / `build` 全绿；截图存档
- [x] **T011**（可并行）favicon：`index.html` + `public/favicon.svg`（简单 SVG，AI 字母标）

## Phase B — 对话页深度优化

- [x] **T012** `MarkdownRenderer.vue`：marked → DOMPurify → v-html；highlight.js 核心 + 10 语言按需注册；主题联动代码高亮 CSS；复制按钮；流式光标（C-01）
- [x] **T013** `ChatView.vue` 消息气泡：MarkdownRenderer 接入（用户 + 助手）、头像、时间（`relative-time`）、复制按钮
- [x] **T014** `ChatView.vue` 工具调用轨迹：消息下方可折叠「工具调用」区（工具名 + 结果摘要）；**数据以现有消息字段为限，拿不到的字段降级并在完成记录说明**
- [x] **T015** `src/lib/session-group.ts` + `frontend/test/session-group.test.mjs`：今天/昨天/更早分组（保持输入顺序、空组不渲染）
- [x] **T016** `ChatView.vue` 会话列表：分组渲染 + 相对时间副标题
- [x] **T017** 走查：Markdown 四类语法渲染抽查 + `<script>` payload 不执行 + 流式光标/停止 + `test:logic` / `type_check` / `build` 全绿；截图存档

## Phase C — 总览页 Dashboard 化

- [x] **T018** `HomeView.vue` 统计卡行：待办 / 逾期 / 今日日程（或即将到来）/ 记忆数 / 文档数；单卡失败降级「—」（FR-009）
- [x] **T019** `HomeView.vue` 能力卡片网格：图标 + 标题 + 一句说明 + 点击跳转（替换纯文字 bullet list）
- [x] **T020** 走查：统计数字与模块实际一致 + 点击跳转 + 构建全绿；截图存档

## Phase D — 任务页（表格重构 + 看板 + 批量）

- [x] **T021** `src/lib/board.ts` + `frontend/test/board.test.mjs`：`groupTasksByStatus`（三列、列内保序）
- [x] **T022** `TasksView.vue`：PageHeader/FilterBar/AppPagination/StatusTag 套用；mini 统计卡；表格默认按截止升序（无截止排最后）
- [x] **T023** `TasksView.vue` 看板视图：三列 + 列头计数 + 空列空态 + 卡片操作按钮（完成/编辑/删除，**无拖拽**）；视图模式持久化 `os.tasks.view`
- [x] **T024** `TasksView.vue` 批量操作：表格多选 + 批量完成/删除（`Promise.allSettled` 汇总报告，C-05 失败语义）
- [x] **T025** 走查：双视图切换/记忆、批量成功与部分失败、构建全绿；截图存档

## Phase E — 知识库页（Tab 化 + 拖拽上传）

- [x] **T026** `KnowledgeView.vue`：`el-tabs` 三 Tab（文档/检索/问答），偏好持久化 `os.knowledge.tab`
- [x] **T027** `KnowledgeView.vue` 拖拽上传：dragover 高亮 / drop 复用现有校验 / 拒绝提示（C-06）
- [x] **T028** `KnowledgeView.vue` 表格：PageHeader/AppPagination 套用 + 片段数/字符数列头 tooltip
- [x] **T029** 走查：Tab 切换、拖入支持/不支持文件、构建全绿；截图存档

## Phase F — 日程页四项

- [x] **T030** `CalendarView.vue` 提醒批量确认：「全部知道了」`Promise.allSettled` 逐条 acknowledge（C-05 同款语义）
- [x] **T031** `CalendarView.vue` 新建表单分组：基本信息 / 时间 / 重复与提醒三组，视觉分隔
- [x] **T032** `CalendarView.vue` 月视图来源色点（日程=主色 / 任务=warning）+ 通知横幅 granted 时折叠为一行
- [x] **T033** 走查：批量确认、表单分组、色点、构建全绿；截图存档

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

### Phase F（日程页：批量确认 + 表单分组 + 月视图色点）

- 门禁：`type-check` 0 错误；`test:logic` 299/299；`build` 通过。
- 真机：8 条到点提醒点「全部知道了」→ 全部清除、卡片消失、toast「已确认 8 条提醒」；新建表单三组（基本信息/时间/重复与提醒）；月视图 26 个色点（25 重复 + 1 一次性）；通知横幅 granted 态折叠为一行；控制台零报错。
- 偏差（T032 降级）：原规格按「日程/任务」来源着色，但 CalendarOccurrence 没有来源字段（后端 DTO 不带 sourceType，纯前端拿不到）→ 降级为「重复 vs 一次性」着色，扫读月历时区分每周都有的与仅此一次的同样有用。已在视图注释与 spec 记录。

### Phase E（知识库页：Tab 化 + 拖拽上传）

- 门禁：`type-check` 0 错误；`test:logic` 299/299；`build` 通过。
- 真机：三 Tab 切换正常、刷新后记住所在 Tab（问答）；拖入 .md 填入文件名并上传成功（文档 26→27）；拖入 .exe 被拒并提示「不支持的文件类型」；检索「索引」3 hits；控制台零报错。
- 测试脚本两次误报（应用无问题）：① 计数断言没排除隐藏 pane（el-tab-pane 隐藏时仍在 DOM）；② 400 Bad Request 实为后端「过短文档拒收」（<350 字符），测试文件太短。均已修正为对的口径。
- 实现要点：el-tabs/el-radio-group 的 v-model 直接写 ref，会绕过 setter——uiStore 的视图偏好持久化从 setter 改为 watch，两条写入路径（v-model 与命令式）都覆盖。

### Phase D（任务页：看板 + 批量 + 表格重构）

- 门禁：`type-check` 0 错误；`test:logic` 299/299（+14 board）；`build` 通过。
- 真机：表格默认按截止升序（无截止保持稳定原序）；勾选 2 条 → 批量栏出现 →「已完成 2 条」汇总提示；看板三列 待办3/进行中0/已完成2（空列有空态）；刷新后视图记忆为看板；控制台零报错。
- 实现要点：批量成败计数放在 store（batchComplete/batchDelete 返回 {succeeded, failed}）——store 原有 remove/toggleCompleted 自己吞错误，allSettled 看它们永远 fulfilled，在视图层数不出失败（tasks.md Notes #1 的落地）。
- 测试插曲：board.test.mjs 首跑挂在自己的期望值笔误（升序断言少写了排最后的 '4'），修正后全绿。

### Phase C（总览页 Dashboard 化）

- 门禁：`type-check` 0 错误；`test:logic` 285/285；`build` 通过。
- 真机：统计卡 5 项数字与接口真实数据逐一核对一致（待办 5 / 逾期 0 / 今日日程 1 / 记忆 2 / 文档 26，用后端直连脚本交叉验证）；点击统计卡正确跳转；控制台零报错。
- 实现取舍：今日日程走直连 `listOccurrences`（当天 00:00–23:59:59 本地区间）而非 calendarStore——store 的 loadOccurrences 会改写全局查询窗口，首页一次统计会把日程页默认窗口冲掉（跨页污染且无提示）。任务/记忆/知识库走各自 store（加载即各页默认视图，顺带预热缓存）。
- 单卡失败降级「—」经 Promise.allSettled 保证（FR-009）。

### Phase B（对话页深度优化）

- 门禁：`type-check` 0 错误；`test:logic` 285/285（+12 session-group）；`build` 通过，assets **1212 KB**（基线 1048 / 预算 1362，marked+hljs+dompurify 全量落地后仍在预算内）。
- 真机：Markdown 四类语法渲染全部通过（清单/代码块/粗体/无裸语法泄漏）、代码高亮生效、复制按钮在；XSS payload（`<script>` + `img onerror`）被 DOMPurify 完全消毒（script 标签 0、onerror 0、`window.__xss` 未设置）；工具轨迹折叠区展示真实审计数据（createTask · 成功 · 4ms）；会话分组 + 相对时间渲染正确；控制台零报错。
- 偏差：T014 原计划「消息字段拿不到就降级」，实测审计接口 `/agent/tool-invocations` 的 `conversationId` 参数后端一直支持（活动页没用过）→ 做成**真实轨迹**，无需降级、无需新接口。
- 教训 1（R-07 补丁）：`optimizeDeps.include` 只列根包对**子路径**不生效——`highlight.js/lib/core` 是 CJS，必须逐个列出 12 条子路径，否则「生产构建绿、dev 白屏」（与配置注释里 dayjs 插件子路径同一约束）。
- 教训 2：hljs 11 没有可注册的 `plaintext` 语言，`hljs.highlight(text, {language:'plaintext'})` 会抛 "Unknown language"——该异常穿过 marked.parse 把 Vue 渲染整页带崩（连环 pageerror）。未识别语言必须走 `escapeHTML` 原文展示。另 hljs 11 不再导出 `escapeHTML`，自写四行转义。
- 教训 3：Playwright 脚本在 HMR 热更新窗口期跑会命中「按钮 disabled」假故障——改动后先等 HMR 稳定再跑验收。

### Phase A（设计系统与全局框架）

- 门禁：`type-check` 0 错误；`test:logic` 273/273（基线 248 + 新增 25）；`build` 通过，assets 1056 KB（基线 1048 KB，新依赖尚未被视图引用，tree-shaking 后几乎无增长）。
- 真机：亮/暗 8 页截图各一套；主题切换后 dataset/class/localStorage 三处一致，刷新保持；暗色下下拉 / 日期面板 / 编辑弹窗 / 通知四个浮层无白块（SC-003）；控制台零报错。
- 环境插曲：验收时发现后端（8080）与项目 PG（55432）已随上一会话进程回收而宕掉 → `docker compose up -d` + 后端重启后恢复。**与本次改动无关**，但记入当日 log 提醒后续会话先探活。
- 偏差 1：marked 实际稳定版 18.1.1（research 初稿按 15.x 估体积），已回改 research.md。
- 偏差 2：新增依赖白名单补入 `@element-plus/icons-vue`（T008 导航图标所需，初稿漏算），已回改 research.md。
- 教训：vite.config 的 optimizeDeps 改动后必须 `rm -rf node_modules/.vite` 再重启 dev（R-07），本次按此执行，dev server 一次起效。
