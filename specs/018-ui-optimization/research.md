# Research: 018-ui-optimization

> 编号 `R-01…`。每条含**决策 / 依据（带实测）/ 被否决方案及理由**。
> 被测决方案更有价值——它们记录了「为什么不是想当然的那个」。

## R-01: Markdown 渲染引擎选型

**决策**：`marked`（稳定主线 15.x，npm 稳定版）。

**依据**：
- 本应用只需「模型输出的 Markdown → HTML」单向渲染，不需要编辑器 / 预览器组件。
- 实测包体（gzip）：marked ~30 KB；markdown-it ~45 KB（含默认 preset）；`@kangc/v-md-editor` ~120 KB+（编辑器全家桶）。
- marked 支持同步渲染（流式期间每帧调一次 `marked.parse`），API 面最小（`marked.parse(src)` + `marked.setOptions`）。

**被否决**：
- `markdown-it`：功能更全（插件生态）但本场景用不到，体积更大。
- `@kangc/v-md-editor` / `md-editor-v3`：为「编辑器」设计，本应用只读渲染，属过度设计（宪法原则 I）。
- 自研渲染器：Markdown 子集（表格 / 嵌套列表 / 代码围栏）边界case多，零依赖收益抵不过正确性风险。

## R-02: 代码高亮策略

**决策**：`highlight.js` 11.x **按需注册语言**（`highlight.js/lib/core` + 手动注册 java / javascript / typescript / python / sql / bash / json / xml / yaml / markdown / plaintext），主题用 `github` / `github-dark` 两个 CSS，随主题切换。

**依据**：
- 全量 `highlight.js`（全部语言 + 全部主题）gzip 后 ~90 KB，超体积预算；核心 + 10 语言 ~20 KB。
- 本产品是个人 AI OS，代码语言分布集中在 JVM / 前端 / SQL / 脚本，10 种覆盖足够；漏网语言回落 `plaintext`（不高亮但不报错）。
- 暗色主题 CSS 与亮色分开加载，由主题状态切换 `<link>`，避免两套常驻。

**被否决**：
- PrismJS：需手动包 `prismjs/components/prism-*.js` 且对异步高亮支持差；highlight.js 的 `highlightAuto` 对未知语言更宽容。
- 全量引入 highlight.js：SC-001 体积预算（130%）不允许无差别全量。

## R-03: 渲染安全（DOMPurify）

**决策**：`dompurify` 3.x，marked 输出后、写入 `v-html` / `innerHTML` 前必须过 `DOMPurify.sanitize(html)`。

**依据**：
- 渲染的输入是**模型输出 + 用户输入**，两者都不可信。知识库文档内容同样会进对话上下文。
- 宪法原则 II（边界即安全）：虽然该条原文针对后端 Agent 边界，但「模型输出直接进 DOM」是本阶段新开的攻击面，按同一精神处置。
- 实测：本应用消息内容可包含任意文本；`v-html` 不过消毒 = 存储型 XSS（单用户也中招：恶意文档入库即可触发）。
- DOMPurify gzip ~20 KB，是本清单里性价比最高的一项。

**被否决**：
- 只依赖 marked 的转义：marked 默认**不转义**原始 HTML（`headerIds` 等选项管不了 `<script>`），官方文档明确要求配消毒器。
- CSP 代替消毒：本地单用户应用无服务端响应头可控面（前端 dev server 代理），且 CSP 不能防 `javascript:` URL 之外的所有 DOM 注入场景；消毒是最后一道也是必需的一道。

## R-04: 暗色模式方案

**决策**：Element Plus 官方暗色变量表 `element-plus/theme-chalk/dark/css-vars.css`，在 `html.dark` 下引入；应用自绘部分全部走 EP 变量（现状已如此，实测仅 4 处硬编码 hex），不收编自建暗色表。

**依据**：
- 实测：全站仅 4 处硬编码颜色（App.vue / main.css / LoginView.vue），其余 100+ 处已是 `var(--el-*)` → 官方暗色表一套变量翻转即可覆盖 96% 界面。
- 自建暗色表 = 维护两份颜色映射，与 EP 升级对抗，无收益。
- `color-scheme: light` 当前写死在 main.css → 改为随 `html.dark` 切换（`color-scheme: dark`），修复滚动条 / 原生控件深色残留。

**被否决**：
- 仅 `prefers-color-scheme` 媒体查询：用户不能手动覆盖（顶栏切换是 FR-002 硬需求）。
- 运行时动态生成暗色变量（tinycolor 反转）：EP 已提供官方表，重复造轮子。

## R-05: 令牌架构

**决策**：两层。`src/styles/tokens.css` 定义 `--os-*`（应用语义令牌：间距 / 圆角 / 阴影 / 字体节奏 / 动效），颜色**不另起炉灶**，直接消费 EP 的 `--el-*`；组件与页面样式只用 `--os-*` 与 `--el-*`，禁止裸值。

**依据**：
- 颜色双轨（--os- 与 --el- 两套）必然漂移；EP 的颜色体系（含 9 级 primary 阶梯）已够用且暗色模式免费跟随。
- 间距 / 圆角 / 阴影 EP 没有对应变量，是真正需要补的洞 → `--os-*` 只放这些。
- 实测当前重复值：`gap: 16px`（卡片间距）×10+、`border-radius: 8px`×8、`font-size: 12/13px`×20+ → 令牌化直接消重。

**被否决**：
- 全部自建 `--os-*` 颜色阶梯：与 R-04 冲突，双轨维护。
- CSS-in-JS / Tailwind：引入构建插件与原子类体系，改动面覆盖全部模板，违背「最小diff达成目标」。

## R-06: 主题持久化形态

**决策**：Pinia `useUiStore`，状态 `'light' | 'dark' | 'system'` 三态；`system` 时监听 `matchMedia('(prefers-color-scheme: dark)')` 变化；解析值写到 `document.documentElement.dataset.theme`（`light`/`dark`）与 class（`.dark` 供 EP 暗色 CSS 选择器）；localStorage key `os.theme`。

**依据**：
- 三态是标配（首次跟随系统、用户可覆盖、可恢复跟随）；二态会丢失「跟随系统」能力。
- EP 暗色 CSS 的选择器是 `html.dark`（官方约定），class 必须加在 `<html>` 上而非 `#app`。
- localStorage 按浏览器维度足够：本地单用户、无多端同步需求（spec Assumptions）。

**被否决**：
- cookie：无服务端消费方，徒增请求体积。
- 只存解析值（二态）：丢失 system 语义，用户在系统切换主题后行为不一致。

## R-07: 新增依赖与 Vite optimizeDeps 的联动（本仓库特有约束）

**决策**：`marked` / `highlight.js` / `dompurify` 加入 `package.json` dependencies 后，**必须同步加进 `vite.config.ts` 的 `optimizeDeps.include`**，并按配置注释既有约定先 `rm -rf node_modules/.vite` 再启动 dev。

**依据**：
- 实测配置：`noDiscovery: true` + 显式 include 列表（2026-10-14 为解决自动导入插件触发重优化崩溃而设）。新依赖不在列表里 → dev 期 CJS/ESM 互操作报错（如 `does not provide an export named 'default'`），而**生产构建不受影响**——典型的「构建绿、dev 崩」不对称失败。
- 三者均为纯 ESM 或双格式（marked ESM；highlight.js 双格式；dompurify 双格式），加 include 是 dev 预打包所需，生产 tree-shaking 不受影响。

**被否决**：
- 只加 package.json 不加 include：dev server 崩，浪费半天（配置注释原话）。
- 关闭 noDiscovery：回到 2026-10-14 之前的崩溃路径。

## R-08: 公共组件形态

**决策**：`src/components/` 下放 `PageHeader.vue` / `FilterBar.vue` / `EmptyState.vue` / `AppPagination.vue` / `StatusTag.vue` / `MarkdownRenderer.vue` / `ThemeToggle.vue`，依赖 `unplugin-vue-components` 默认目录约定（`src/components`）自动注册，**不手动 import、不全局 main.ts 注册**。

**依据**：
- 项目已装 `unplugin-vue-components` 且 `Components()` 未传 `dirs` → 默认扫描 `src/components`；现有 10 个视图全部直接裸用 EP 组件，无手动注册先例。
- 自动注册与 EP 的按需引入同源（宪法 follow-up TODO #2 已落地），风格一致。

**被否决**：
- main.ts 全局注册：绕过按需机制，与既有约定冲突。
- 每页手动 import：11 处 import 样板，且漏 import 只在运行时炸。

## R-09: 相对时间 / 关键词高亮等纯函数

**决策**：自写纯函数进 `src/lib/`（`relative-time.ts` / `highlight-text.ts` / `session-group.ts`），配 `.mjs` 单测；**不引 dayjs**。

**依据**：
- `frontend/test/` 是自定义 `.mjs` 运行器，测纯函数零摩擦（既有 `calendar-dates.ts` / `search-routes.ts` 先例）。
- dayjs 只是 EP 的传递依赖，不在 package.json → 直接用违反「依赖显式声明」；为核心功能加 direct dep 又不值得（宪法原则 I）。
- 单测覆盖 spec 定死的边界：59s/60s/23h/24h/7d。

**被否决**：
- dayjs direct dep：一个 `fromNow()` 引入 7 KB + 传递依赖显式化成本。
- `Intl.RelativeTimeFormat`：Safari 15.4 之前不支持紧凑格式，且「3 小时前」这类中文输出要自行拼格式串，未必更短。

## R-10: 知识库 Tab / 任务视图模式的持久化

**决策**：Tab 与视图模式均为组件本地 state + localStorage（key `os.knowledge.tab` / `os.tasks.view`），**不进 URL query**。

**依据**：
- 这两个偏好是「个人习惯」而非「可分享状态」（对比：全局搜索关键词必须进 URL——既有实现已如此，`/search?q=` 可分享）。
- 进 URL 会让 `/knowledge?tab=ask` 这类链接出现在分享 / 书签场景，语义重。

**被否决**：
- URL query：可分享性对本场景是负资产（spec 明确这两类非分享状态）。
- Pinia 持久化插件（pinia-plugin-persistedstate）：多一个依赖；localStorage 读写各 3 行，自写更直。

## R-11: 批量确认 / 批量删除的并发与失败语义

**决策**：`Promise.allSettled` 并发调用现有单条接口；汇总成功 / 失败数，用一条 ElMessage 报告（失败列出条数，不逐条弹）。

**依据**：
- 后端无批量接口且本阶段不新增（spec 边界）；`allSettled` 保证部分失败不抛断。
- 逐条弹 toast：8 条提醒 = 8 次打断；汇总一条是数量信息不是事件流。
- 失败行不回滚已成功行（接口无跨行事务，回滚需再调删除接口，复杂且非用户预期）。

**被否决**：
- 串行 for-await：8 次串行 Round-trip 肉眼可见地慢（本地 ~5ms/次尚可，但语义上无顺序依赖）。
- 任一条失败整体回滚：需要逆向接口，超出「纯前端」边界。

## R-12: 搜索结果关键词高亮

**决策**：纯函数 `highlightSegments(text, keyword)` 返回 `[{text, hit}]` 片段数组，模板里 `v-for` 渲染；大小写不敏感；keyword 按空白切分多词（任一命中即高亮）。

**依据**：
- 与 R-09 同一模式：纯函数可单测（边界：空串 / 特殊字符 / 重叠命中）。
- 不用正则替换 HTML：关键词含 `<` `&` 时替换 `v-html` 会引入注入面；片段数组 + 文本插值天然免疫。

**被否决**：
- `v-html` + 正则高亮：注入面（用户输入直接进 URL query，`?q=<img onerror>`），为高亮开 XSS 口子不值。
- 第三方高亮组件：为一个 `mark` 引入依赖。

## 汇总：新增依赖（白名单，全部进 package.json + optimizeDeps.include）

| 包 | 版本策略 | gzip 估算 | 用途 |
|---|---|---|---|
| marked | ^18.x（安装时稳定主线 18.1.1；初稿按 15.x 估体积，实测同量级） | ~30 KB | Markdown → HTML（R-01） |
| highlight.js | ^11.12.0 | ~20 KB（核心+10 语言） | 代码高亮（R-02） |
| dompurify | ^3.4.17 | ~20 KB | HTML 消毒（R-03） |
| @element-plus/icons-vue | ^2.3.2 | 按图标计（每个 ~1 KB；nav 8 + 主题 2 + 搜索 1） | 导航图标 / 主题切换 / 搜索前缀（Phase A 补入白名单：T008 要求导航带图标，~11 个图标下内联 SVG 反而冗长） |

合计 ~70 KB gzip + 图标 ~11 KB，约占当前 gzip 总量（~180 KB）的 45% → 在 SC-001 的 130% 预算内；若超标，降级顺序：highlight.js 语言数 10→6、marked 换 `marked/marked.min.js` 直引（放弃 tree-shaking 收益换体积）。
