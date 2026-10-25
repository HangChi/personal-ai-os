# Data Model: 018-ui-optimization

本特性**纯前端**，不新增 / 不修改任何后端实体、DTO 或接口。
以下是新增的前端数据结构与存储键。

## 新增前端数据结构

### ThemePreference（主题偏好）

```ts
type ThemePreference = 'light' | 'dark' | 'system'
```

- 存储：`localStorage['os.theme']`，值即上表三选一；缺省视为 `'system'`
- 解析：`system` 时读 `matchMedia('(prefers-color-scheme: dark)')`，媒体查询变化时实时跟随
- 落地形态：解析值写入 `document.documentElement.dataset.theme`（`'light' | 'dark'`）
  与 `document.documentElement.classList.toggle('dark', ...)`（EP 暗色 CSS 选择器为 `html.dark`）

### DesignTokens（设计令牌）

`src/styles/tokens.css` 中的 CSS 自定义属性，分四组：

| 组 | 前缀 | 示例 | 说明 |
|---|---|---|---|
| 间距 | `--os-space-*` | `--os-space-1: 4px` … `--os-space-6: 24px` | 替代散落的 `gap: 8/12/16px` |
| 圆角 | `--os-radius-*` | `--os-radius-sm: 6px` / `md: 8px` / `lg: 12px` | 替代硬编码 6/8/10px |
| 阴影 | `--os-shadow-*` | `card` / `card-hover` / `popup` | EP 无对应语义变量 |
| 字体节奏 | `--os-font-*` | `--os-font-xs: 12px` / `sm: 13px` / `md: 14px` | 收敛 12/13/14 三档混用 |

**颜色不自建**：一律消费 EP `--el-*`（含暗色翻转）。收编现有 4 处硬编码 hex（App.vue 2 处、main.css 1 处、LoginView.vue 2 处中的实际值）。

### UiStore（Pinia）

```ts
interface UiState {
  theme: ThemePreference        // 用户偏好（三态）
  resolvedTheme: 'light' | 'dark'  // 解析值，供 ThemeToggle 图标显示
  tasksView: 'table' | 'board'  // 任务页视图（localStorage 'os.tasks.view'）
  knowledgeTab: 'documents' | 'search' | 'ask'  // 知识库 Tab（localStorage 'os.knowledge.tab'）
}
```

### 纯函数模块（`src/lib/`，全部有 `.mjs` 单测）

| 模块 | 导出 | 契约要点 |
|---|---|---|
| `theme.ts` | `resolveTheme(pref, systemDark)` / `applyTheme(resolved)` | `resolveTheme('system', true) === 'dark'` |
| `relative-time.ts` | `relativeTime(iso, now)` | 边界：<60s 刚刚 / <60min N 分钟前 / <24h N 小时前 / <7d N 天前 / 否则 MM-DD |
| `session-group.ts` | `groupSessionsByDay(items, now)` | 分组键 `today` / `yesterday` / `earlier`，保持输入顺序 |
| `highlight-text.ts` | `highlightSegments(text, keyword)` | 返回 `{text, hit}[]`；空 keyword → 单段全不命中；大小写不敏感；特殊字符原样返回不做正则 |
| `board.ts` | `groupTasksByStatus(tasks)` | 三列 TODO / DOING / DONE，列内保持输入顺序 |

## 明确不做

- 不改任何后端实体 / 迁移（本特性零 Flyway 脚本）
- 不做 Pinia 持久化插件：localStorage 读写在各 store 内各 3 行
- 不做跨端同步：localStorage 按浏览器维度
