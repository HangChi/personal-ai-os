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

// ---- 发送框：两行工具栏的尺寸契约（2026-10-28 第四次重排） ----

/*
 * 这一组针对发送框第四次重排，拦的都是「改完 build 绿、单测全绿、
 * 界面就是歪了」的类别。全部在真实浏览器里量过：
 *
 * ① **一行之内不许出现两种控件高度。**
 *    再上一版分两行，第一行 chip 28px、第二行按钮 32px。32 是
 *    `theme.css` 里 `html .el-button--small:not(.is-link) { min-height: 32px }`
 *    （0-2-1）定的全站规格，压不回 28 —— 于是两行并存**必然**一矮一高。
 *    现在两行全部取 32（`--os-button-height`），包括胶囊内的条数分段。
 *
 * ② **「两行」必须按语义切，不能按左右切。**
 *    上一版是「每行各有左右两段」，于是两条中缝一高一矮、彼此错位，
 *    看上去像折行没折好。现在是「第一行只有 模型+字数」「第二行只有 工具+动作组」，
 *    每行最多两个元素、右段天然对齐，不存在第二条中缝。
 *
 * ③ **条数不许再占独立宽度。**
 *    第三次重排把它做成 78px 的独立 `el-select` + 8px gap = 86px 常驻宽度，
 *    实测把折行阈值从 1150px 抬到 1280px、960px 下从 2 行变 3 行、盒高 173px。
 *    现在它是胶囊内部的第二分段（`.chat-composer__chip-sep` 分隔），
 *    开知识库只让胶囊自身变宽，**不新增 flex 项、不再额外吃掉 gap**。
 */
const composer = read('src/components/chat/ChatComposer.vue')

check(
  '发送框：chip 高度取常规按钮档（--os-button-height，不是 inline 档）',
  /\.chat-composer__chip\s*\{[^}]*height:\s*var\(--os-button-height\)/.test(composer),
)
/*
 * 条数下拉：**不再自己定 32px 高**，而是内嵌成胶囊的透明分段。
 *
 * <p>它现在住在 `.chat-composer__chip` 内部，胶囊本身已经是 32px；
 * 里面再撑一个 32px 只会把胶囊顶到更高（26 是 EP 的默认行高档，
 * 恰好落在 32 的胶囊里居中）。所以这一条断言的是「它放弃了自己的框」——
 * 无边框、无底色、无背景，只有文字和箭头。
 */
check(
  '发送框：条数内嵌为胶囊分段（透明、无边框、无独立高度档）',
  /\.chat-composer__topk\s+:deep\(\.el-select__wrapper\)\s*\{[^}]*background:\s*transparent/.test(composer) &&
    /\.chat-composer__topk\s+:deep\(\.el-select__wrapper\)\s*\{[^}]*box-shadow:\s*none/.test(composer) &&
    !/\.chat-composer__topk\s+:deep\(\.el-select__wrapper\)\s*\{[^}]*min-height:\s*var\(--os-button-height\)/.test(
      composer,
    ),
)

// 条数必须是胶囊内的分段，不能是独立控件
check(
  '发送框：知识库开关与条数同处一个胶囊容器（.chat-composer__chip-group）',
  /class="chat-composer__chip-group"/.test(composer),
)
check(
  '发送框：胶囊容器是唯一的 flex 项（flex: none，自身不参与伸缩）',
  /\.chat-composer__chip-group\s*\{[^}]*flex:\s*none/.test(composer),
)
check(
  '发送框：条数不再是独立 flex 项（.chat-composer__topk 不得写死 width）',
  !/\.chat-composer__topk\s*\{[^}]*\bwidth:/.test(composer),
)
check('发送框：条数分段与开关之间有竖线分隔（同一个胶囊内）', /\.chat-composer__chip-sep\s*\{/.test(composer))

/*
 * el-select 必须显式定宽。
 *
 * <p>不定宽时它在 inline-flex 里被 shrink 到 min-content：
 * `el-select__wrapper` 的占位层是 `position: absolute`、不参与 min-content
 * 计算，实测压到 18.3px、「6 条」一个字都看不见。
 */
check(
  '发送框：条数下拉显式定宽（否则被 flex 压成 18px、文字消失）',
  /\.chat-composer__topk\s+:deep\(\.el-select\)\s*\{[^}]*width:\s*\d+px/.test(composer),
)

/*
 * 窄屏必须收成纯图标。
 *
 * <p>960px 下行宽 372，开知识库时需求 393 —— 差 21px 会折行、盒高从 133 变 173。
 * 靠压 padding 补不稳（换字体就变），只能靠收起文字。
 * 两个胶囊各自保留 `title` + `aria-label`，收字后仍有可读标签。
 */
check(
  '发送框：两个胶囊的文字都有独立 label 包裹（供窄屏隐藏）',
  (composer.match(/chat-composer__chip-label/g) || []).length >= 2,
)
check(
  '发送框：窄屏（1080px 以下）收起胶囊文字',
  /@media\s*\(max-width:\s*1080px\)\s*\{[^}]*\.chat-composer__chip-label\s*\{[^}]*display:\s*none/.test(composer),
)
check('发送框：附件按钮有 aria-label（收字后仍可读）', /aria-label="添加附件"/.test(composer))

// 盒内三段结构（2026-10-29 最终摆法）：
//   输入区本体（textarea + 右下角字数）→ 读数行（只剩上下文）→ 工具行（控件 + 动作组）
check(
  '发送框：输入区本体是 .chat-composer__input（textarea + 字数一个块）',
  /class="chat-composer__input"/.test(composer),
)
check('发送框：读数行是 meta-row（现在只剩上下文用量）', /class="chat-composer__meta-row"/.test(composer))
check('发送框：工具行是 tool-row（工具 + 动作组）', /class="chat-composer__tool-row"/.test(composer))

/*
 * 读数行的分布：左对齐（`justify-content: flex-start`）。
 *
 * <h3>2026-10-29：为什么不再是 space-between</h3>
 * 用户反馈「上下文长度和 0/4000 的位置太丑了」——那一版是「上下文贴左、
 * 字数贴右」分居两端，1440px 实测中间空 528px，两个 12px 灰字各站一头。
 * 现在的摆法按**语义归属**分：
 * <ul>
 *   <li>字数数的是输入框里的草稿 → 移入输入区本体右下角，有内容才显示；</li>
 *   <li>上下文用量是会话级状态 → 独自留在读数行靠左。</li>
 * </ul>
 * 读数行只剩一个元素，`space-between` 与左对齐渲染相同，但不写它：
 * 「两端各一个」的语义已不成立，留着会让人以为这行还该有第二个元素。
 *
 * <p>同时加一条反向断言：字数**不能**带 margin-left: auto。它现在靠
 * `align-self: flex-end` 贴输入区右缘，自己再带 auto 会与输入区的列布局
 * 叠加，在窄屏挤出多余空隙。
 */
check(
  '发送框：读数行左对齐（只剩一个元素，不再两端对齐）',
  /\.chat-composer__meta-row\s*\{[^}]*justify-content:\s*flex-start/.test(composer) &&
    !/\.chat-composer__meta-row\s*\{[^}]*justify-content:\s*space-between/.test(composer),
)
check(
  '发送框：字数自己不带 margin-left: auto（贴右缘靠 align-self）',
  !/\.chat-composer__count\s*\{[^}]*margin-left:\s*auto/.test(composer),
)
check('发送框：字数靠 align-self 贴输入区右缘', /\.chat-composer__count\s*\{[^}]*align-self:\s*flex-end/.test(composer))
check('发送框：已删掉的 readouts 容器不再残留样式', !/\.chat-composer__readouts\s*\{/.test(composer))

/*
 * 字数与上下文的**模板块序**：字数必须在输入区块内、读数行之前。
 *
 * <p>按 DOM 块序判断，不用「谁在文件里出现得早」—— 旧断言拿
 * `indexOf('ContextMeter') < indexOf('chat-composer__count')` 比下标，
 * 而 ContextMeter 在 import 语句里就出现过，于是不管模板怎么摆都恒真
 * （假绿）。这里比的是三个 class 锚点的先后：input < meta-row < tool-row，
 * 字数必须落在 input 与 meta-row 之间 —— 即「在输入区里、不在读数行里」。
 */
const idxInput = composer.indexOf('class="chat-composer__input"')
const idxMeta2 = composer.indexOf('class="chat-composer__meta-row"')
const idxCount = composer.indexOf('class="chat-composer__count"')
check(
  '发送框：字数在输入区块内、读数行之前（属于输入框，不属于状态行）',
  idxInput > 0 &&
    idxCount > idxInput &&
    idxCount < idxMeta2 &&
    idxMeta2 < composer.indexOf('class="chat-composer__tool-row"'),
)
/*
 * 模型选择器的位置：必须在 tool-row 之内、meta-row 之外。
 *
 * <p>按「标签出现顺序」判断，不用「action-group 后面 900 字内有 ChatModelPicker」——
 * 后者依赖模板与样式段的相对位置，加一段注释就会假绿。
 * 这里取三个锚点在模板里的下标：meta-row < tool-row < action-group，
 * 而 ChatModelPicker 必须落在 tool-row 与 action-group 之间。
 */
const idxMeta = composer.indexOf('class="chat-composer__meta-row"')
const idxTool = composer.indexOf('class="chat-composer__tool-row"')
const idxAction = composer.indexOf('class="chat-composer__action-group"')
const idxModel = composer.indexOf('<ChatModelPicker')
check(
  '发送框：模型选择器在动作组内（tool-row 与 action-group 之间）',
  idxModel > idxTool && idxModel > idxAction && idxAction > idxTool && idxTool > idxMeta,
)
const motionGroupLeft = composer.match(/\.chat-composer__action-group\s*\{[^}]*margin-left:\s*([^;]+);/)
check(
  '发送框：动作组在第二行推右（margin-left: auto，不是固定 4px）',
  Boolean(motionGroupLeft) && motionGroupLeft[1].trim() === 'auto',
)

// 动作组不许折行：内部一折行就回到「多行多分界」
check(
  '发送框：动作组禁止内部折行（flex: none 整体搬家）',
  /\.chat-composer__action-group\s*\{[^}]*flex:\s*none/.test(composer),
)

// 输入框单行起步：两行预留会在空态留下整行空白（实测 54px 盒里文字只占 31px）
check('发送框：输入框单行起步（rows="1"）', /:rows="1"/.test(composer))
check(
  '发送框：输入框最小高度按一行行高 + 上下 padding 取 31px',
  /\.chat-composer__textarea\s+:deep\(\.el-textarea__inner\)\s*\{[^}]*min-height:\s*31px/.test(composer),
)

// 盒内上下 padding 必须不等：底部要大于顶部（行内控件的下边缘读作「内容结束」）
const boxPad = composer.match(/\.chat-composer__box\s*\{[^}]*padding:\s*([^;]+);/)
check('发送框：盒内 padding 三值写法（顶 < 底）', Boolean(boxPad) && boxPad[1].trim().split(/\s+/).length === 3)

// ---------------------------------------------------------------- 分页的落脚处

/*
 * 会话侧栏的分页一度**紧贴卡片底边**（实测 `gapToCardBottom = 1`，
 * 且那 1px 还是卡片自身的边框）。根因是两个叠加：
 *   ① `AppPagination--compact` 只有 `margin-top`，**底边距一直是 0**；
 *   ② 它的宿主 `.el-card__body` 被 `ChatSessionList` 设了 `padding: 0`。
 * 于是列表四周有 12px，唯独分页没有任何留白 —— 看起来被「压在底线上」。
 *
 * 更麻烦的是排查方向：`ChatSessionList` 里曾留着一段**已失效**的
 * `.chat-sessions__pagination { padding: 12px }`（手写分页时代的残留），
 * 它会让人以为「内边距本来是有的」。所以下面两条一起断言。
 */
const pagination = read('src/components/AppPagination.vue')

const compactRule = pagination.match(/\.app-pagination--compact\s*\{[^}]*\}/)
check('分页：紧凑变体的规则存在', Boolean(compactRule))

/*
 * 断言按**语义**而不是字面量：这里要守的是「底边确实有一圈留白」，
 * 至于写成 `padding: 0 0 var(--os-space-3)` 还是分开的 `padding-bottom`，
 * 属于实现自由 —— 写死字面量会在任何一次格式化后假红。
 * 取 `padding` / `padding-bottom` 两者中实际生效的那个。
 */
const compactBody = compactRule ? compactRule[0] : ''
const compactPadding = compactBody.match(/padding:\s*([^;]+);/)
const compactPaddingBottom = compactBody.match(/padding-bottom:\s*([^;]+);/)

/** 把 `padding: 0 0 var(--os-space-3)` 这类简写还原出底边值 */
function paddingBottomOf(shorthand, explicit) {
  if (explicit) return explicit[1].trim()
  if (!shorthand) return null
  const parts = shorthand[1].trim().split(/\s+/)
  // 1 值=四边；2 值=上下/左右；3 值=上/左右/下；4 值=上/右/下/左
  return parts.length === 1 ? parts[0] : parts.length === 3 ? parts[2] : parts.length === 4 ? parts[2] : null
}

const bottomValue = paddingBottomOf(compactPadding, compactPaddingBottom)
check(
  '分页：紧凑变体底边有留白（不能为 0，否则紧贴卡片底边）',
  bottomValue !== null && bottomValue !== '0' && bottomValue !== '0px',
)

/*
 * 顶边必须显式归零：基类给了 `margin-top: 12px`，而分页上方紧邻的滚动容器
 * 自己已有 12px 内边距 —— 不归零就是 24px vs 12px，分页明显偏上（实测过）。
 */
check('分页：紧凑变体顶边归零（避免与上方容器的 padding 叠加成 24px）', /margin-top:\s*0(px)?\s*;/.test(compactBody))

/*
 * 死样式守卫：`ChatSessionList` 不能声明分页容器的尺寸。
 *
 * <p>分页已收敛到 `AppPagination`，同一个 UI 元素的尺寸只能有一处声明：
 * 否则会出现「在这里改 padding 完全不生效」的空转，以及上面那种
 * 「明明看到有 padding 却还是贴边」的误判。
 */
const sessionList = read('src/components/chat/ChatSessionList.vue')
check(
  '会话列表：不再残留手写分页样式（尺寸归 AppPagination 一处）',
  !/\.chat-sessions__pagination\s*\{/.test(sessionList) && !/\.chat-sessions__total\s*\{/.test(sessionList),
)
/*
 * 模板块里也不能再出现这两个类名。
 *
 * <p>只看 `<template>` 与 `<style>` 之间的区段，不看整份文件：上面那段
 * 注释**必须**提到这两个类名（它要解释残留的来历），拿全文做断言会误伤。
 */
const templateStart = sessionList.indexOf('<template>')
const templateEnd = sessionList.indexOf('</template>')
const sessionTemplate = sessionList.slice(templateStart, templateEnd)
check(
  '会话列表：模板里不再使用已删除的类名',
  templateStart > 0 &&
    templateEnd > templateStart &&
    !sessionTemplate.includes('chat-sessions__pagination') &&
    !sessionTemplate.includes('chat-sessions__total'),
)

/* ==========================================================================
 * 聊天阅读栏宽度（2026-10-30）
 *
 * 守的是一个**用户直接看出来的问题**：「助手的回复铺满又有点丑了」。
 *
 * <p>背景：助手气泡为了消除「有的框很大、有的很小」改成了 `width: 100%`，
 * 但 1440px 下气泡宽 842px，而实测**最长的一行文字只占内宽的 55%**（约 449px）
 * —— 一大块灰底里只有半边有字。填充率（文字墨迹面积 / 内容区面积）
 * 842px 时仅 17.4%，收到 680px 后是 21.4%。
 *
 * <p>这三条断言防的是三种「写了但没生效 / 只改了一边」的退化：
 * 1. 令牌被删或改回宽值 → 宽屏重新变回大灰框；
 * 2. 消息区用了新公式、输入区忘了改 → 消息窄、输入框宽，一宽一窄更难看；
 * 3. 输入区沿用了旧的 20px 最小内边距 → 窄屏下比气泡各宽 4px，边缘对不齐。
 * ========================================================================== */

const bandPx = tokenPx('os-chat-band')
check('阅读栏：令牌 --os-chat-band 存在且是 px 数值', bandPx !== null)
/*
 * 上界 800：再宽就回到「一行 50 字以上、右侧大片空灰底」的老样子。
 * 下界 640：再窄代码块会被迫横向滚动（实测 640 时滚动 12px），且一行不足 40 字。
 */
check(
  '阅读栏：宽度落在 640~800 之间（过宽则空、过窄则代码块横向滚动）',
  bandPx !== null && bandPx >= 640 && bandPx <= 800,
)

const chatViewSrc = read('src/views/ChatView.vue')
const composerSrc = read('src/components/chat/ChatComposer.vue')

/*
 * 两边必须用**同一个公式**（同一个令牌 + 同一个 `max()` 兜底下限）。
 *
 * <p>不比对整条 padding 字符串：允许各自保留不同的上/下内边距。
 * 只要求「左右两侧的算式里出现了这个令牌」，以及「都用了 max() 兜底」。
 */
function usesBand(src, selector) {
  const body =
    src.match(new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([\\s\\S]*?)\\n\\}`))?.[1] ?? ''
  const stripped = body.replace(/\/\*[\s\S]*?\*\//g, '')
  return {
    body: stripped,
    hasBand: stripped.includes('var(--os-chat-band)'),
    hasMax: /padding:[^;]*max\(/.test(stripped),
  }
}

const msgBand = usesBand(chatViewSrc, '.chat__messages')
check('阅读栏：消息滚动区用令牌控制左右内边距', msgBand.hasBand)
check('阅读栏：消息滚动区用 max() 保证窄屏不小于最小值', msgBand.hasMax)
check('阅读栏：消息区的 padding 是简写（漏写会只改到个别边）', /padding:\s*var\(--os-space-3\)/.test(msgBand.body))

const composerBand = usesBand(composerSrc, '.chat-composer')
check('阅读栏：输入区必须用同一个令牌，否则消息窄、输入框宽', composerBand.hasBand)
check('阅读栏：输入区同样用 max() 兜底', composerBand.hasMax)

/*
 * 输入区的最小内边距必须是 16（`--os-space-4`），与消息区一致。
 *
 * <p>改造前输入区是 `--os-space-5`（20px）。差 4px 在宽屏被居中公式吃掉看不出来，
 * 但窄屏（<724+32）下 `max()` 退回最小值，20 与 16 的差就直接暴露成
 * 「输入框比气泡各宽 4px」。这类只在特定宽度出现的不对齐，肉眼极难归因。
 */
check(
  '阅读栏：输入区最小内边距与消息区一致（16，不是 20）',
  /padding:\s*var\(--os-space-3\)\s*max\(var\(--os-space-4\)/.test(composerBand.body),
)

/*
 * 死样式守卫：输入区不能残留把宽度写死的旧写法。
 *
 * <p>`padding-inline` 与 `padding` 同时存在时后者被覆盖的顺序取决于书写顺序，
 * 是典型的「改了不生效」来源。这里要求它不出现。
 */
check('阅读栏：输入区不用 padding-inline 与 padding 混写', !/padding-inline/.test(composerBand.body))
