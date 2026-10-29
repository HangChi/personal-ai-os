/**
 * 页面排版体检：把「肉眼看不出但确实有问题」的指标量出来。
 *
 * 只做三件可验证的事，不做主观评价：
 *   1. 横向溢出（document.scrollWidth > innerWidth）—— 375/768/1280 三个宽度各测一次；
 *   2. 关键容器的溢出与空白（列表行是否被挤压、内容区是否有大段空白）；
 *   3. 控制台 error / 未捕获异常（渲染出错但界面看起来正常的那些）。
 *
 * 用法：NODE_PATH=<ws>/node_modules node scripts/dev/ui-audit.mjs
 * 截图输出到 /tmp/ui-audit/<page>-<width>.png
 */
import { mkdirSync } from 'node:fs'
import { createRequire } from 'node:module'

// ESM 的 import 不认 NODE_PATH，而 playwright 装在 WorkBuddy 的共享工作区里。
// 用 createRequire 走 CJS 解析（它认 NODE_PATH），再按常见位置兜底。
const require = createRequire(import.meta.url)
function loadPlaywright() {
  const candidates = [
    'playwright',
    `${process.env.HOME}/.workbuddy/binaries/node/workspace/node_modules/playwright`,
  ]
  for (const candidate of candidates) {
    try {
      return require(candidate)
    } catch {
      // 试下一个
    }
  }
  throw new Error('找不到 playwright；请先在工作区安装：npm i playwright')
}

const { chromium } = loadPlaywright()

const BASE = 'http://127.0.0.1:5173'
const OUT = '/tmp/ui-audit'
const WIDTHS = [1280, 768, 375]

const PAGES = [
  ['home', '/'],
  ['chat', '/chat'],
  ['tasks', '/tasks'],
  ['calendar', '/calendar'],
  ['knowledge', '/knowledge'],
  ['memory', '/memory'],
  ['activity', '/activity'],
  ['usage', '/usage'],
  ['settings', '/settings'],
]

mkdirSync(OUT, { recursive: true })

// 优先用缓存里的 headless shell：playwright 期望的版本号可能比本地缓存新
const EXECUTABLES = [
  `${process.env.HOME}/Library/Caches/ms-playwright/chromium_headless_shell-1243/chrome-headless-shell-mac-arm64/chrome-headless-shell`,
  `${process.env.HOME}/Library/Caches/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-mac-arm64/chrome-headless-shell`,
]

async function launch() {
  for (const executablePath of EXECUTABLES) {
    try {
      return await chromium.launch({ executablePath })
    } catch {
      // 试下一个
    }
  }
  return chromium.launch({ channel: 'chrome' })
}

const browser = await launch()
const findings = []

for (const [name, path] of PAGES) {
  for (const width of WIDTHS) {
    const context = await browser.newContext({
      viewport: { width, height: 900 },
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai',
      deviceScaleFactor: 1,
    })
    const page = await context.newPage()
    const errors = []
    page.on('console', (message) => {
      if (message.type() === 'error') errors.push(message.text().slice(0, 200))
    })
    page.on('pageerror', (error) => errors.push(`uncaught: ${String(error).slice(0, 200)}`))

    try {
      await page.goto(BASE + path, { waitUntil: 'networkidle', timeout: 20000 })
      await page.waitForTimeout(700)

      const metrics = await page.evaluate(() => {
        const doc = document.documentElement
        const overflowX = doc.scrollWidth - window.innerWidth
        // 找出真正把页面撑宽的元素（比根节点宽度还宽、且不是滚动容器）
        const offenders = []
        for (const el of document.querySelectorAll('body *')) {
          const rect = el.getBoundingClientRect()
          if (rect.width === 0) continue
          if (rect.right > window.innerWidth + 1) {
            const style = getComputedStyle(el)
            if (style.overflowX === 'auto' || style.overflowX === 'scroll') continue
            offenders.push({
              tag: el.tagName.toLowerCase(),
              cls: (el.className || '').toString().slice(0, 60),
              right: Math.round(rect.right),
              width: Math.round(rect.width),
            })
          }
        }
        offenders.sort((a, b) => b.right - a.right)

        // 另一类问题：容器把自己 overflow:hidden，内容比它宽 —— 但**要排除三种正常情况**，
        // 否则满屏都是假警报（第一版就误报了一堆）：
        //   1. 元素带 text-overflow: ellipsis —— 设计上就是省略号截断，配 tooltip 即可看全；
        //   2. el-table 的外层 wrapper —— 它自己是 hidden，真正的横向滚动在内层
        //      .el-scrollbar__wrap 上（overflow-x: auto），能滑就不算丢内容；
        //   3. 子元素里已经存在可横向滚动的容器。
        const isScrollable = (el) => {
          for (const node of [el, ...el.querySelectorAll('*')]) {
            const s = getComputedStyle(node)
            if ((s.overflowX === 'auto' || s.overflowX === 'scroll') && node.scrollWidth > node.clientWidth + 1) {
              return true
            }
          }
          return false
        }
        const clipped = []
        for (const el of document.querySelectorAll('body *')) {
          const style = getComputedStyle(el)
          if (style.overflowX !== 'hidden') continue
          if (style.textOverflow === 'ellipsis') continue
          // 表头容器与表体同宽、靠 JS 同步横向滚动，它自己不会滚也不算丢内容
          if (el.classList.contains('el-table__header-wrapper')) continue
          const hidden = el.scrollWidth - el.clientWidth
          if (hidden <= 8 || el.clientWidth <= 0) continue
          if (isScrollable(el)) continue
          clipped.push({
            tag: el.tagName.toLowerCase(),
            cls: (el.className || '').toString().slice(0, 60),
            hiddenPx: Math.round(hidden),
            clientWidth: Math.round(el.clientWidth),
          })
        }
        clipped.sort((a, b) => b.hiddenPx - a.hiddenPx)

        // 桌面宽度下表格仍需要横向滑动，说明列宽算错了（不是响应式的取舍）
        const wideTables = []
        if (window.innerWidth >= 1200) {
          for (const el of document.querySelectorAll('.el-table__body-wrapper .el-scrollbar__wrap')) {
            const over = el.scrollWidth - el.clientWidth
            if (over > 8) {
              wideTables.push({ overPx: Math.round(over), client: Math.round(el.clientWidth), scroll: Math.round(el.scrollWidth) })
            }
          }
        }

        return {
          overflowX,
          innerWidth: window.innerWidth,
          bodyHeight: document.body.scrollHeight,
          offenders: offenders.slice(0, 4),
          clipped: clipped.slice(0, 3),
          wideTables,
        }
      })

      // viewport 截图（不是 fullPage）：内部有滚动容器的页面（对话页）
      // fullPage 会把容器内容按文档高度重排，截出来的样子和用户所见不一致
      await page.screenshot({ path: `${OUT}/${name}-${width}.png` })

      if (metrics.overflowX > 0) {
        findings.push({
          page: name,
          width,
          kind: '页面横向溢出',
          detail: `超出 ${metrics.overflowX}px`,
          offenders: metrics.offenders,
        })
      }
      if (metrics.clipped.length > 0) {
        findings.push({
          page: name,
          width,
          kind: '内容被硬裁',
          detail: metrics.clipped
            .map((c) => `${c.tag}.${c.cls} 藏了 ${c.hiddenPx}px（容器 ${c.clientWidth}px）`)
            .join('；'),
        })
      }
      if (metrics.wideTables.length > 0) {
        findings.push({
          page: name,
          width,
          kind: '桌面端表格仍需横滑',
          detail: metrics.wideTables
            .map((t) => `超出 ${t.overPx}px（可视 ${t.client} / 内容 ${t.scroll}）`)
            .join('；'),
        })
      }
      if (errors.length > 0) {
        findings.push({ page: name, width, kind: '控制台报错', detail: errors.slice(0, 3).join(' | ') })
      }
    } catch (cause) {
      findings.push({ page: name, width, kind: '加载失败', detail: String(cause).slice(0, 200) })
    } finally {
      await context.close()
    }
  }
}

await browser.close()

console.log(`\n截图目录: ${OUT}`)
if (findings.length === 0) {
  console.log('未发现横向溢出与控制台报错。')
} else {
  console.log(`\n发现 ${findings.length} 项：`)
  for (const item of findings) {
    console.log(`  [${item.kind}] ${item.page} @${item.width}px — ${item.detail}`)
    for (const offender of item.offenders ?? []) {
      console.log(`      ${offender.tag}.${offender.cls} right=${offender.right} w=${offender.width}`)
    }
  }
}
