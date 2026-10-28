import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { check, eq } from './assert.mjs'

/**
 * 视觉规格的自洽性（阶段 27）。
 *
 * <p>这一组断言针对的都是「改错了不报错、界面上也不一定一眼看出来」的东西，
 * 而且都在真实浏览器里踩过一次：
 *
 * <ul>
 *   <li>行高 token 容不下行内按钮 → 表格行被内容撑开，同一个 token 出现两种行高；
 *   <li>单选组的字号规则被 EP 的 small 修饰符压过（0-1-0 vs 0-2-0）→ 写了等于没写；
 *   <li>危险色四个形态各用各的红 → 同一个删除动作换个形态就换颜色。
 * </ul>
 *
 * 这里不去解析 CSS 语义，只做**数值一致性**检查：token 之间的关系、
 * 以及「同一条规则在浅色与暗色下都必须被显式声明」。这能拦住大部分
 * 「只改浅色忘了暗色」「只改一个 token」的回退。
 */

const here = dirname(fileURLToPath(import.meta.url))
const read = (rel) => readFileSync(resolve(here, '..', rel), 'utf8')

const tokensCss = read('src/styles/tokens.css')
const themeCss = read('src/styles/theme.css')

/** 从 tokens.css 取一个 px 数值 token */
function tokenPx(name) {
  const m = tokensCss.match(new RegExp(`--${name}:\\s*(\\d+)px`))
  return m ? Number(m[1]) : null
}

/**
 * 取一个尺寸 token 的 px 值，允许它引用 `--os-space-N`。
 *
 * <p>`--os-table-cell-padding-y` 写的是 `var(--os-space-2)` 而不是字面量 ——
 * 断言如果只认 px 会误判「令牌不存在」，而它其实只是走了间距刻度。
 */
function sizePx(name) {
  const direct = tokenPx(name)
  if (direct !== null) return direct
  const ref = tokensCss.match(new RegExp(`--${name}:\\s*var\\(--os-space-(\\d)\\)`))
  if (!ref) return null
  return tokenPx(`os-space-${ref[1]}`)
}

// ---- 表格行高必须容得下最高的单元格内容 ----

const rowH = tokenPx('os-table-row-height')
const padY = sizePx('os-table-cell-padding-y')
const btnInline = tokenPx('os-button-height-inline')
const btnHeight = tokenPx('os-button-height')

check('令牌存在：表格行高', rowH !== null)
check('令牌存在：单元格上下内边距', padY !== null)
check('令牌存在：行内按钮高度', btnInline !== null)
check('令牌存在：常规按钮高度', btnHeight !== null)

// 单元格可用高度 = 行高 - 上下内边距 - 1px 下边框。
// 必须 >= 行内按钮高度，否则按钮会把行撑开（实测知识库被撑到 45px、活动停在 44px）。
const available = rowH - padY * 2 - 1
check(`表格行高容得下行内按钮（可用 ${available}px >= 按钮 ${btnInline}px）`, available >= btnInline)

// 常规按钮比行内按钮高一档，且两档都要能塞进单元格
check('按钮档位递增：行内 < 常规', btnInline < btnHeight)

// ---- 单选组：必须把 small/large 修饰符一起写进选择器 ----

// EP 的 `.el-radio-button--small .el-radio-button__inner` 是 0-2-0；
// 只写 `.el-radio-button__inner`（0-1-0）会被压过 —— 这个 bug 实测存在过：
// 规则写了 font-size: 13px，浏览器里仍是 12px。
check(
  '单选组样式覆盖了 small 修饰符（否则被 EP 的 0-2-0 压过）',
  themeCss.includes('.el-radio-button--small .el-radio-button__inner'),
)
check(
  '单选组样式带 html 前缀（不依赖打包顺序）',
  themeCss.includes('html .el-radio-button--small .el-radio-button__inner'),
)

// 单选组高度必须达到 WCAG 2.5.8 的 24px 下限；这里抬到了常规按钮同档
const radioDecl = themeCss.slice(themeCss.indexOf('html .el-radio-button--small .el-radio-button__inner'))
check('单选组高度对齐常规按钮档位', radioDecl.slice(0, 600).includes('min-height: var(--os-button-height)'))

// ---- 危险色：浅色与暗色都必须显式声明，不能只改一边 ----

check('暗色覆盖存在（文件里确实有 html.dark 段）', themeCss.split('html.dark').length - 1 > 10)

/*
 * 「暗色必须有对应声明」比「红色值不许太多」有意义得多。
 *
 * 危险色天然需要多档（静息 / hover / active / 禁用 × 浅色 / 暗色），
 * 统计色值数量只会得到噪声。真正会出错的是**只写了浅色、忘了暗色**：
 * 暗色主色被 EP 提亮，浅色的那一档压上去就会掉对比度（本项目实测过
 * 白字 2.77:1、link 悬停 2.32:1 等）。所以逐个形态检查两边都在。
 */
const DANGER_VARIANTS = [
  ['实底-静息', 'html.dark .el-button--danger:not(.is-plain):not(.is-text):not(.is-link) {'],
  ['实底-禁用', 'html.dark .el-button--danger:not(.is-plain):not(.is-text):not(.is-link).is-disabled,'],
  ['link-悬停', 'html.dark .el-button--danger.is-link:not(.is-disabled):hover'],
  ['plain-静息', 'html.dark .el-button--danger.is-plain:not(.is-disabled) {'],
  ['plain-悬停', 'html.dark .el-button--danger.is-plain:not(.is-disabled):hover'],
]

for (const [label, needle] of DANGER_VARIANTS) {
  check(`危险按钮暗色显式声明存在：${label}`, themeCss.includes(needle))
}

// plain 款浅色基线抬高特异性后会反过来压过暗色，所以暗色必须再声明一次字色
const plainLightIdx = themeCss.indexOf('html .el-button--danger.is-plain:not(.is-disabled) {')
const plainDarkIdx = themeCss.indexOf('html.dark .el-button--danger.is-plain:not(.is-disabled) {')
check('plain 危险按钮有浅色字色声明', plainLightIdx > 0)
check('plain 危险按钮的暗色声明在浅色之后（同特异性靠顺序决胜）', plainDarkIdx > plainLightIdx)

// 表格行内操作区必须共用同一份样式，不应在各视图里重复定义
check('主题层提供 .os-table-actions', themeCss.includes('.os-table-actions {'))

// ---- 确认弹窗：换行与危险键的样式契约 ----

check(
  '确认弹窗正文允许换行（pre-wrap）',
  /\.os-confirm\s+\.el-message-box__message\s+p[^{]*\{[^}]*white-space:\s*pre-wrap/.test(themeCss),
)

eq('确认弹窗类名在 lib 与 theme 中一致', read('src/lib/confirm.ts').includes("'os-confirm'"), true)

// ---- 表格：EP fit 机制与表头变量特异性 ----

/*
 * 这两条是本轮（2026-10-29）在真实浏览器里踩出来的，都属于
 * 「改完不报错、build 绿、单测全绿，但界面就是不对」的类别。
 *
 * ① **EP 的 fit 只在「存在无 width 的列」时才摊剩余空间。**
 *    见 element-plus/es/components/table/src/table-layout.mjs:102：
 *      if (flexColumns.length > 0 && fit) {...} else { bodyWidth = bodyMinWidth }
 *    flexColumns = `!isNumber(column.width)` 的列。把每列都写死 `width` 之后
 *    表格内容宽恒等于列宽之和，容器有余量时**右侧整片空白**、表头分隔线
 *    只画到内容右端（实测用量表 innerW 578 / wrapW 998，空掉 420px）。
 *    所以数据列的宽度必须用 `min-width`。
 *
 * ② **`--el-table-header-bg-color` 必须挂在 `html` 前缀下。**
 *    EP theme-chalk 里就有 `.el-table{--el-table-header-bg-color:...}`
 *    （0-1-0），我们若用裸 `.el-table` 也是 0-1-0 —— 同特异性下靠注入顺序决胜，
 *    而路由懒加载让 EP 的 table 样式在本页运行时才注入、晚于 theme.css，
 *    于是 EP 赢：表头被解析成 #ffffff，与白卡片同色 ⇒ **表头没有底色**。
 *    这个坑 2026-10-29 实测过，改 `html .el-table` 后 headerBg=#fafbfc。
 */

const usageView = read('src/views/UsageView.vue')
const activityView = read('src/views/ActivityView.vue')
const knowledgeTab = read('src/components/knowledge/KnowledgeDocumentsTab.vue')

// 表头背景变量必须在 html 前缀的规则里声明（否则被 EP 同特异性覆盖成白色）
check(
  '表头背景变量挂在 `html .el-table` 下（裸 .el-table 会被 EP 覆盖成白底）',
  /html\s+\.el-table\s*\{[^}]*--el-table-header-bg-color:/.test(themeCss),
)

// 暗色那一档特异性必须更高，否则改不了
check('表头变量暗色档是 html.dark .el-table', /html\.dark\s+\.el-table\s*\{/.test(themeCss))

/*
 * 列宽断言只看「有没有把 width 写死」。
 *
 * 注意：一个文件里可能有多张表（活动页同时有「工具调用」与「MCP 服务」），
 * 所以按 `<el-table>` 起始标签**切段**再分别检查，不能对整个文件一把抓
 * —— 否则一张表固定宽度、另一张弹性，会被混成同一个集合而误判。
 */
const tableBlocks = (src) => src.split(/<el-table[\s>]/).slice(1)

for (const [name, src] of [
  ['用量明细', usageView],
  ['工具调用', activityView],
]) {
  for (const [i, block] of tableBlocks(src).entries()) {
    const tags = block.match(/<el-table-column[^>]*>/g) || []
    // 排除勾选列与纯操作列：它们本就该固定宽度
    const cols = tags.filter((t) => !t.includes('type="selection"') && !t.includes('label="操作"'))
    if (cols.length === 0) continue
    /*
     * 唯一必须成立的契约：**至少有一个 min-width 列**。
     * 有了它，EP 才会进入 `flexColumns.length > 0 && fit` 分支把富余摊开；
     * 一个都没有（全部 width）就会留下右侧空白。
     *
     * 至于固定列的个数不设上限：MCP 表就是 4 固定 + 1 弹性（说明列吸收富余），
     * 只要那个弹性列存在，布局就是完整的。所以只查「有没有」。
     */
    check(
      `${name}表#${i + 1} 至少有一个 min-width 列（否则 EP fit 不摊空间、右侧留白）`,
      cols.some((t) => /min-width="/.test(t)),
    )
  }
}

// 知识库表必须保留弹性列（标题是长文本，需要吃掉富余）
check('知识库表标题列用 min-width（长文本列吸收富余）', /prop="title"[^>]*min-width=/.test(knowledgeTab))
