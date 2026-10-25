# Feature Specification: 全站 UI 优化（设计系统 + 逐页重构）

**Feature Branch**: `018-ui-optimization`

**Created**: 2026-10-26

**Status**: Approved

**Input**: 用户描述：「帮我优化现在的 UI，每个模块都要优化」；经确认：按 A→H 全量推进、纯前端不动接口、允许新增 `marked` + `highlight.js` + `dompurify`、每段真机截图验收。

## 背景与实测基线（2026-10-26 真机走查 + 构建实测）

- 全站 8 页真机截图走查完成（`/tmp/ui-audit/`），浏览器控制台零报错。
- 生产构建通过：`dist` 总量 **1.0 MB**（assets 1048 KB），主 chunk `index-*.js` 50.21 KB（gzip 18.55 KB）。
- 全站硬编码十六进制颜色仅 **4 处**（`App.vue` / `styles/main.css` / `LoginView.vue`），其余全部使用 Element Plus CSS 变量 → 主题化改造基础好、风险低。
- 当前 `styles/main.css` **零** Element Plus 主题变量覆盖 → 全套默认蓝色主题；`color-scheme: light` 写死。
- 视图规模：10 个 `.vue` 共 3590 行，最大 `CalendarView.vue` 1014 行。
- 核心痛点：助手消息 Markdown 原样显示（`-**标题**` 裸文本）；全站裸卡片平铺无层次；知识库四卡竖叠超长滚动；日程 8 条提醒需逐条点「知道了」。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 设计令牌与深色模式 (Priority: P1)

作为用户，我打开应用时看到统一的视觉风格（圆角、阴影、间距、字体节奏一致），并且可以在顶栏一键切换深色 / 浅色主题，刷新后选择不丢失。

**Why this priority**: 这是其余全部页面的地基。没有令牌层，每页美化都是各自为政的补丁；深色模式依赖令牌层，且 Element Plus 官方暗色套件要求先有变量覆盖。

**Independent Test**: 只做本故事也可独立验收：访问任意页面，切换主题按钮，刷新页面主题保持；用 DevTools 检查卡片圆角 / 阴影来自令牌变量而非硬编码。

**Acceptance Scenarios**:

1. **Given** 用户首次访问, **When** 打开任意页面, **Then** 卡片、按钮、输入框使用统一令牌（圆角 / 阴影 / 间距），视觉风格一致
2. **Given** 浅色主题, **When** 点击顶栏主题切换按钮, **Then** 全站在 200ms 过渡内切换到深色，无残留白块
3. **Given** 用户已选择深色, **When** 刷新页面或关闭重开浏览器, **Then** 仍是深色（localStorage 持久化）
4. **Given** 用户从未选择过主题, **When** 操作系统为深色模式, **Then** 首次访问跟随系统偏好
5. **Given** 深色主题, **When** 查看任意表格 / 表单 / 弹窗, **Then** 文字对比度满足可读（无深底深字）

---

### User Story 2 - 全局框架重构（顶栏 / 响应式 / 公共组件） (Priority: P1)

作为用户，我在任何页面都有一致的页面头（标题 + 描述 + 操作区）、一致的筛选栏与分页、一致的空态；顶栏导航带图标、搜索框加宽且支持 `Ctrl+K`；窄窗口下表格可横向滚动而不是挤爆。

**Why this priority**: 与故事 1 共同构成地基；「每页重复写 card header + filters + pagination」是当前最大的重复代码源。

**Independent Test**: 逐个页面走查：页面头 / 筛选栏 / 分页 / 空态样式一致；窗口缩到 375px 宽，所有页面无横向溢出（表格容器内部滚动除外）。

**Acceptance Scenarios**:

1. **Given** 任意业务页, **When** 页面渲染, **Then** 使用统一的 `PageHeader`（标题 + 可选描述 + 右侧操作区）
2. **Given** 用户按 `Ctrl+K`（macOS 为 `Cmd+K`）, **When** 在任意页面, **Then** 焦点落入顶栏搜索框
3. **Given** 浏览器窗口宽 375px, **When** 访问任务 / 知识库 / 活动页, **Then** 表格在自身容器内横向滚动，页面主体不产生横向滚动条
4. **Given** 顶栏, **When** 查看导航, **Then** 每个导航项有图标，当前项高亮清晰
5. **Given** 任意列表为空, **When** 渲染空态, **Then** 使用统一 `EmptyState` 组件（插槽可放引导文案 / 操作按钮）

---

### User Story 3 - 对话页：Markdown 渲染与消息体验 (Priority: P1)

作为用户，我在对话里看到的助手回复是渲染后的 Markdown（标题、列表、代码块带高亮），每条消息有头像、时间和复制按钮；流式输出时有光标指示；我还能看到助手这一轮调用了哪些工具。

**Why this priority**: 对话是核心页面，当前 Markdown 裸文本显示是第一痛点；工具调用轨迹让「助手做了什么」可见，与活动页互补。

**Independent Test**: 在对话页发送一条会触发 Markdown 回复的消息（如「用 Markdown 列一个三项清单并给一段 Java 代码」），检查渲染结果；发送含 `<script>` 的输入，检查不执行。

**Acceptance Scenarios**:

1. **Given** 助手回复含 Markdown 语法, **When** 流式结束, **Then** 渲染为对应 HTML（标题 / 列表 / 粗体 / 链接 / 代码块）
2. **Given** 回复含代码块, **When** 渲染, **Then** 代码带语法高亮且可一键复制
3. **Given** 任意消息内容含 `<script>` 或 `onerror=` 等, **When** 渲染, **Then** 被消毒掉，不执行（DOMPurify）
4. **Given** 任意消息, **When** 悬停消息气泡, **Then** 显示复制按钮，点击后提示已复制
5. **Given** 助手流式生成中, **When** 查看消息区, **Then** 有打字光标 / 「生成中」指示，且消息区自动滚动到底部
6. **Given** 助手本轮调用了工具, **When** 查看该轮回复, **Then** 消息下方有可展开的「工具调用」区域，列出工具名与结果摘要
7. **Given** 用户消息, **When** 渲染, **Then** 用户消息同样按 Markdown 渲染（与助手一致），但样式区分左右

---

### User Story 4 - 会话列表分组与相对时间 (Priority: P2)

作为用户，我在左侧会话列表看到按「今天 / 昨天 / 更早」分组的会话，每条显示相对更新时间（如「3 小时前」）。

**Why this priority**: 会话多以后当前平铺列表难以定位；分组是低成本高感知改进。

**Independent Test**: 创建几个会话（或使用已有数据），查看分组与相对时间渲染。

**Acceptance Scenarios**:

1. **Given** 存在今天 / 昨天 / 更早的会话, **When** 打开会话列表, **Then** 按三组分组展示，组标题中文
2. **Given** 任意会话, **When** 渲染, **Then** 副标题显示相对时间（刚刚 / N 分钟前 / N 小时前 / 昨天 / MM-DD）
3. **Given** 相对时间计算, **When** 单测 `relativeTime()` 纯函数, **Then** 覆盖各区间边界（59s / 60s / 23h / 24h / 7d）

---

### User Story 5 - 总览页 Dashboard 化 (Priority: P2)

作为用户，打开首页即看到关键数字（今日待办、逾期、即将到来的日程、记忆条数、文档数）和带图标可跳转的能力卡片，而不是一屏纯文字。

**Why this priority**: 首页是门面；当前「已有能力」是纯文字 bullet list，无数据概览。

**Independent Test**: 访问首页，统计卡数字与各模块实际数据一致；点击能力卡片跳到对应页面。

**Acceptance Scenarios**:

1. **Given** 首页, **When** 加载完成, **Then** 顶部展示统计卡行（≥4 个指标），数字来自现有接口（任务 / 日程 / 记忆 / 知识库）
2. **Given** 统计卡, **When** 点击, **Then** 跳到对应模块页面
3. **Given** 某个统计接口失败, **When** 渲染, **Then** 该卡显示「—」而不阻塞其他卡（局部降级）
4. **Given** 能力卡片, **When** 悬停, **Then** 有视觉反馈（上浮 / 阴影加深），点击跳转对应页

---

### User Story 6 - 任务页：表格重构 + 看板视图 + 批量操作 (Priority: P2)

作为用户，我可以用看板视图（待办 / 进行中 / 已完成三列）管理任务，可以在表格里多选任务并批量完成 / 删除，表格默认按截止时间排序（无截止的排最后）。

**Why this priority**: 任务是高频模块；看板与批量操作是感知最强的功能补齐。

**Independent Test**: 任务页切换看板 / 表格两种视图；勾选多条任务批量完成；空列显示空态。

**Acceptance Scenarios**:

1. **Given** 任务页, **When** 切换到看板视图, **Then** 三列分别展示 TODO / DOING / DONE 任务，列头显示计数
2. **Given** 看板视图, **When** 某列无任务, **Then** 该列显示空态文案而非空白
3. **Given** 表格视图, **When** 勾选多行, **Then** 顶部出现批量操作栏（批量完成 / 批量删除），显示已选数量
4. **Given** 批量删除, **When** 确认后执行, **Then** 逐条调用现有删除接口（不做新接口），失败的行在结果提示中说明
5. **Given** 表格默认排序, **When** 未选任何排序, **Then** 按截止时间升序，无截止时间排最后
6. **Given** 用户选择的视图, **When** 刷新页面, **Then** 记住上次视图（localStorage）

---

### User Story 7 - 知识库页：Tab 化 + 拖拽上传 (Priority: P3)

作为用户，知识库页通过 Tab 在「文档 / 检索 / 问答」之间切换而不是四张巨卡竖叠；上传区支持把文件拖进来。

**Why this priority**: 当前页面需滚动四屏才能看完，信息架构扁平化是主要改进。

**Independent Test**: 知识库页三个 Tab 切换；拖一个 .md 文件到上传区触发选择；拖 .exe 文件被拒并提示。

**Acceptance Scenarios**:

1. **Given** 知识库页, **When** 切换 Tab, **Then** 文档列表 / 检索 / 问答三块内容切换，URL 不带变化（本地 state）
2. **Given** 上传区, **When** 拖入支持的类型文件, **Then** 与「选择文件」等效（填入文件名与标题）
3. **Given** 上传区, **When** 拖入不支持的类型或超过 5 MiB, **Then** 拒绝并提示原因，不静默吞掉
4. **Given** 文档表格, **When** 查看列头, **Then** 「片段数 / 字符数」有 tooltip 解释含义

---

### User Story 8 - 日程页：提醒批量确认 + 表单重组 + 月视图着色 (Priority: P3)

作为用户，到点提醒可以一键「全部知道了」；新建日程表单分组清晰；月视图里不同来源（任务 / 日程）的事件用不同颜色圆点区分。

**Why this priority**: 8 条提醒逐条点「知道了」是明确的重复劳动；月视图着色提升扫读效率。

**Independent Test**: 造 ≥2 条到点提醒，点「全部知道了」全部消失；月视图查看颜色圆点。

**Acceptance Scenarios**:

1. **Given** ≥2 条到点提醒, **When** 点击「全部知道了」, **Then** 逐条调用现有确认接口，全部处理后卡片消失
2. **Given** 新建日程表单, **When** 查看, **Then** 字段分组（基本信息 / 时间 / 重复与提醒）有视觉分隔
3. **Given** 月视图, **When** 某天有事件, **Then** 每条事件前有来源色点（日程 / 任务），悬停显示完整标题
4. **Given** 通知权限已授予, **When** 查看页面顶部, **Then** 通知横幅折叠为一行小条（不再占半屏）

---

### User Story 9 - 记忆 / 活动 / 搜索 / 登录收尾 (Priority: P3)

作为用户，记忆页有搜索框；活动页的工具调用记录可切换时间线视图、会话 ID 截断显示；搜索结果关键词高亮；登录页有品牌化视觉。

**Why this priority**: 四个小页的体验补丁，成本低。

**Independent Test**: 各页单独走查对应能力。

**Acceptance Scenarios**:

1. **Given** 记忆页, **When** 输入搜索词, **Then** 列表按内容过滤（纯前端过滤）
2. **Given** 活动页, **When** 切换到时间线视图, **Then** 记录按天分组展示
3. **Given** 活动页表格, **When** 查看会话列, **Then** UUID 截断为前 8 位 + tooltip 显示全量
4. **Given** 搜索结果页, **When** 查看命中, **Then** 标题 / 副标题中命中关键词被高亮
5. **Given** 登录页, **When** 访问, **Then** 有品牌区（Logo / 名称 / 一句说明），与全站风格一致

---

### Edge Cases

- 深色模式下 Element Plus 组件（日期选择器面板、下拉、弹窗、通知）全部跟随，无白色残留 → 验收时逐一打开这些浮层检查
- `prefers-reduced-motion: reduce` 时关闭过渡动画
- Markdown 渲染超长内容（>10000 字符）不卡死页面（marked 同步渲染，需关注；如卡顿则截断预览 + 「展开」）
- 流式输出中途切换会话 / 断网：停止生成、保留已收到内容、错误就地提示
- 看板视图拖拽移动卡片：本期不做跨列拖拽（见「明确不含」），卡片操作仍用按钮
- 批量操作部分失败：成功的生效、失败的报告，不整体回滚（接口无事务）
- 窄屏下看板视图：三列改为纵向堆叠

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 提供设计令牌层（颜色 / 圆角 / 阴影 / 间距 / 字号 / 动效），集中定义在单一来源，各页面 MUST NOT 新增硬编码颜色（现有 4 处一并收编）
- **FR-002**: 系统 MUST 支持深色 / 浅色主题切换，选择 MUST 持久化到 localStorage，未选择时 MUST 跟随 `prefers-color-scheme`
- **FR-003**: 系统 MUST 提供 `PageHeader` / `FilterBar` / `EmptyState` / `Pagination` / `StatusTag` 公共组件并全站复用
- **FR-004**: 顶栏 MUST 支持 `Ctrl/Cmd+K` 聚焦搜索框；导航项 MUST 带图标；MUST 移除硬编码的「阶段 17」标识（该文案每轮过期，属已知坑）
- **FR-005**: 对话页 MUST 用 marked 渲染 Markdown，代码块 MUST 经 highlight.js 高亮；所有渲染 HTML MUST 经 DOMPurify 消毒
- **FR-006**: 消息气泡 MUST 展示头像、时间与复制按钮；流式输出 MUST 有进行中指示
- **FR-007**: 助手轮次 MUST 可展开查看工具调用轨迹（工具名 + 结果摘要；数据来自现有消息 / 活动接口能拿到的字段，不新增接口）
- **FR-008**: 会话列表 MUST 按今天 / 昨天 / 更早分组并显示相对时间
- **FR-009**: 总览页 MUST 展示统计卡行与可跳转能力卡片；单个统计失败时局部降级为「—」
- **FR-010**: 任务页 MUST 提供看板 / 表格双视图（选择持久化），MUST 支持多选批量完成 / 删除，表格 MUST 默认按截止时间排序
- **FR-011**: 知识库页 MUST Tab 化（文档 / 检索 / 问答），上传区 MUST 支持拖拽（含类型与大小校验）
- **FR-012**: 日程页 MUST 支持提醒批量确认；月视图事件 MUST 带来源色点；通知横幅 MUST 可折叠
- **FR-013**: 记忆页 MUST 有内容搜索；活动页 MUST 提供时间线视图与会话 ID 截断；搜索结果 MUST 高亮关键词
- **FR-014**: 全部页面 MUST 在 375px 宽度无横向溢出（表格容器内部滚动除外）
- **FR-015**: 新增纯逻辑（主题持久化、相对时间、会话分组、关键词高亮、看板分组）MUST 有 `.mjs` 单测（遵循 `frontend/test/` 现有约定）

### Key Entities

本特性**纯前端**，不新增后端实体。新增的前端数据结构：

- **ThemePreference**: `'light' | 'dark' | 'system'`，存 localStorage，驱动 `data-theme` 属性与 EP 暗色 class
- **DesignTokens**: CSS 自定义属性集合（`--os-*` 命名空间），映射到 Element Plus `--el-*` 变量
- **UiStore (Pinia)**: 主题状态与切换动作、侧栏 / 视图偏好（任务视图模式等）

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 生产构建通过，`dist` 总量不超过基线的 **130%**（基线 1.0 MB；新增 marked + highlight.js + dompurify 后允许上涨，超预算需在 research.md 说明并做按需引入优化）
- **SC-002**: `npm run type_check`、`npm run test:logic`、`npm run build` 三项全绿；新增单测 ≥ 5 个纯函数用例文件
- **SC-003**: 深色模式下 8 个页面 + 全部浮层（下拉 / 日期面板 / 弹窗 / 通知）无「深底深字」或白块残留（真机截图逐页确认）
- **SC-004**: 375px 宽度下 8 个页面无横向滚动条（表格内部滚动除外）
- **SC-005**: 助手 Markdown 回复渲染正确率人工抽查 10 条（标题 / 列表 / 代码块 / 粗体四类各若干），无裸语法残留
- **SC-006**: 顶栏无硬编码阶段标识；全局搜索 `Ctrl/Cmd+K` 在任意页面可聚焦
- **SC-007**: 每段（A–H）完成后真机截图存档，与走查基线对比有可见改进

## Assumptions

- 纯前端改动，**不新增 / 不修改任何后端接口**；批量确认、工具轨迹等均复用现有接口与字段
- 主题选择按浏览器维度存 localStorage（本地单用户，无账号体系同步需求）
- 「工具调用轨迹」展示的数据来源以现有消息与会话接口返回字段为限；若某字段前端拿不到，该故事降级为只展示已有字段并在 tasks.md 记录
- 任务看板不做跨列拖拽（纯展示 + 按钮操作），避免引入拖拽库
- Element Plus 暗色模式采用官方 `element-plus/theme-chalk/dark/css-vars.css` 方案，不自建暗色变量表

## 明确不含（Out of Scope）

| 项 | 依据 |
|---|---|
| 后端接口任何改动 | 用户确认「纯前端，不动接口」 |
| 跨列拖拽看板 | 需引入拖拽库，违背简单优先；按钮操作已覆盖 |
| 虚拟滚动 / 消息分页加载 | 当前数据量（消息数百条级）不需要，列入观测项 |
| PWA / 离线支持 | 超出 UI 优化范围 |
| 多语言 i18n | 单用户中文产品，无需求 |
| 新增设计系统文档站 | 令牌即文档（CSS 注释），不引入 Storybook |
