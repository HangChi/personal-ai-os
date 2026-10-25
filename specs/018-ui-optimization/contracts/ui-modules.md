# Contracts: 018-ui-optimization（前端模块契约）

本特性零后端契约。以下为**新增前端模块的行为契约**——每份给出判定顺序与必须有的行为用例。

## C-01: MarkdownRenderer 组件

**消费方**：ChatView（消息气泡）、KnowledgeView（问答答案区，如启用）。

**Props / Events**：`props: { content: string; streaming?: boolean }`；无事件（纯展示）。

**行为契约**：

| # | 用例 | 期望 |
|---|---|---|
| 1 | `content` 含 `# 标题` / `- 列表` / `**粗体**` / `` `code` `` | 渲染为对应 HTML 元素 |
| 2 | `content` 含 ` ```java ` 代码围栏 | highlight.js 高亮 + 复制按钮 |
| 3 | `content` 含 `<script>alert(1)</script>` | 消毒后**不出现** script 标签，源码以文本展示 |
| 4 | `content` 含 `<img src=x onerror=alert(1)>` | onerror 属性被剥离 |
| 5 | `content` 为 `''` | 渲染空（不抛错） |
| 6 | `streaming=true` | 尾部显示打字光标（CSS 动画） |
| 7 | `content` > 10000 字符 | 首屏渲染不冻结（实测帧时间 < 100ms；超标则截断 + 「展开全文」） |
| 8 | 深色主题 | 代码块切换 `github-dark` 主题，无白底 |

**判定顺序**：`marked.parse(content)` → `DOMPurify.sanitize(html)` → `v-html`。
顺序即契约：**消毒必须在解析后、注入前**，两步都不可省（R-03）。

## C-02: 主题系统（UiStore + tokens.css + ThemeToggle）

**判定顺序**：
1. 启动：读 `localStorage['os.theme']`（无 → `'system'`）
2. 解析：`'system'` 时取 `matchMedia('(prefers-color-scheme: dark)').matches`
3. 落地：`documentElement.dataset.theme = resolved`；`documentElement.classList.toggle('dark', resolved === 'dark')`
4. EP 暗色 CSS 在 `html.dark` 下生效；`color-scheme` 随 `data-theme` 切换

**行为用例**：

| # | 用例 | 期望 |
|---|---|---|
| 1 | 首次访问，系统浅色 | 浅色，无闪烁（首帧前同步应用，见 C-02 反模式） |
| 2 | 点击切换 → 刷新 | 保持用户选择 |
| 3 | 选 `system`，系统切深色 | 实时跟随，无需刷新 |
| 4 | DevTools 打开日期面板 / 下拉 / 弹窗 / 通知 | 全部深色，无白块 |
| 5 | `prefers-reduced-motion: reduce` | 主题过渡与全局动画关闭 |

**反模式（MUST NOT）**：在 `onMounted` 里才应用主题 → 首帧白闪。必须在 `main.ts` 挂载前同步执行（或 `index.html` 内联一段 10 行内的引导脚本）。

## C-03: 公共组件（PageHeader / FilterBar / EmptyState / AppPagination / StatusTag）

| 组件 | Props | 行为契约 |
|---|---|---|
| `PageHeader` | `title: string`; `description?: string`; 默认插槽（右侧操作） | 标题 + 可选描述 + 右操作区；窄屏操作区换行不遮挡标题 |
| `FilterBar` | 默认插槽（筛选控件） | 横向排列，窄屏 (`<768px`) 纵向堆叠；不自带边框（由卡片提供） |
| `EmptyState` | `description: string`; 默认插槽（引导 / 操作） | EP `el-empty` 封装 + 统一 `image-size=70`；插槽内容居中 |
| `AppPagination` | `total/page/size` + `change(page0)` | 页码 0/1 换算在组件内完成（后端 0 起）；`layout="prev, pager, next"`；`total=0` 不渲染 |
| `StatusTag` | `status: string`; `map: Record<string, {label,type}>` | 未映射值回落 `{label: status, type: 'info'}`，**不抛错**（对齐现有 ActivityView 的 `outcomeMeta` 语义） |

**注册方式**：`src/components/` 目录约定 + `unplugin-vue-components` 自动注册（R-08）；**禁止** main.ts 全局注册或手动 import。

## C-04: 会话分组（ChatView）

- 数据源：现有 `sessions`（含 `updatedAt` 字段的以它为准，无则以列表顺序）
- 分组键：`today`（本地日等于今天）/ `yesterday` / `earlier`；组标题「今天 / 昨天 / 更早」
- 组内顺序保持接口返回顺序（不重排）
- 相对时间显示在每条副标题：`刚刚 / N 分钟前 / N 小时前 / 昨天 / MM-DD`
- 空组不渲染组标题

## C-05: 任务看板与批量操作（TasksView）

- 视图模式持久化 `os.tasks.view`；`board` / `table`
- 看板三列来自 `groupTasksByStatus(tasks)`；列头 = 中文列名 + 计数
- 卡片操作：完成 / 编辑 / 删除（按钮，**不做拖拽**，R-07/spec 明确不含）
- 批量：表格多选（`el-table` selection）；批量完成 = 逐条 `toggleCompleted`；批量删除 = 逐条 `remove`；均走现有 store 方法，**零新接口**
- 失败语义：`Promise.allSettled` 汇总，「成功 X 条，失败 Y 条」一条 message；失败不回滚成功行

## C-06: 拖拽上传（KnowledgeView）

- 事件源：上传卡片的 `dragover` / `dragleave` / `drop`
- `dragover` 时高亮投放区（边框变主色 + 底色浅染）
- `drop`：取 `e.dataTransfer.files[0]` → 复用现有 `onFileChange` 的全部校验（类型 / 5 MiB）
- 拒绝时高亮撤除 + ElMessage 说明原因；**不**吞掉错误
- `dragleave` 到子元素不撤高亮（用计数器或 `pointer-events` 规避闪烁）

## C-07: 关键词高亮（SearchView / MemoryView）

- `highlightSegments(text, keyword)` 返回片段数组；模板 `v-for` 渲染，命中片段包 `<mark>`（样式用 `--el-color-warning-light-9` 底 + 继承字色）
- keyword 为空 / 全空白 → 单段 `hit=false`
- keyword 含正则特殊字符（`*` `(` `[` 等）→ 按字面匹配，不编译正则
- 多词（空白切分）任一命中即高亮；重叠命中取先出现者
