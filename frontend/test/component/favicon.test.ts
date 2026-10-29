import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * favicon 的合法性与与品牌标记的一致性。
 *
 * <p>这个文件针对一次**完全静默的失败**：页签上不显示 logo，但
 * HTTP 200、文件存在、构建产物里也在、`<link rel="icon">` 写法完全正确 ——
 * 页面上没有任何报错，只是图标不出现。
 *
 * <p>根因：`favicon.svg` 里的 XML 注释含有连续短横线（`--el-color-primary`
 * 与中文破折号 `——` 即 U+2014 打两个）。**XML 注释的正文不允许出现 `--`**，
 * 一旦出现整份 SVG 就是非法 XML，浏览器会静默丢弃整个图标。
 *
 * <p>这类错误浏览器控制台不会报（它把资源当作「加载成功但不渲染」），
 * eslint / prettier / vue-tsc / build 也全都不会报 —— 只能靠断言守。
 */

const PUBLIC_DIR = resolve(process.cwd(), 'public')
const SVG_SRC = readFileSync(resolve(PUBLIC_DIR, 'favicon.svg'), 'utf8')
const INDEX_SRC = readFileSync(resolve(process.cwd(), 'index.html'), 'utf8')

/** 抽出所有 XML 注释的正文 */
function commentBodies(src: string): string[] {
  return [...src.matchAll(/<!--([\s\S]*?)-->/g)].map((m) => m[1])
}

describe('favicon.svg：必须是合法 XML', () => {
  it('注释正文里不能出现连续短横线 —— 这会让整份 SVG 变成非法 XML', () => {
    const offenders = commentBodies(SVG_SRC).filter((body) => body.includes('--'))

    // 报错信息带上出事的那一小段，否则「有 3 处 -- 」根本不知道改哪里
    expect(offenders.map((b) => b.slice(0, 80))).toEqual([])
  })

  it('根节点是带 viewBox 的 svg', () => {
    const doc = new DOMParser().parseFromString(SVG_SRC, 'image/svg+xml')

    const root = doc.documentElement
    expect(root.tagName.toLowerCase()).toBe('svg')
    expect(root.getAttribute('viewBox')).toBe('0 0 32 32')
  })

  /*
   * ⚠️ 这里**不能**靠 DOMParser 的 `parsererror` 来守 XML 合法性。
   *
   * 实测：往注释里注入 `--el-color-primary` 之后，
   * - Chrome（真实解析器）：`hasErr: true`，报
   *   "error on line 3 at column 7: Unexpected token '--'"；
   * - happy-dom（vitest 环境）：**`parsererror` 为 null，不报错**。
   *
   * 也就是说那条断言在 vitest 里永远通过 —— 它会给人一种「已经守住了」
   * 的错觉，而实际交付的 SVG 仍然会被浏览器整份丢弃。
   * 所以合法性只能由上面那条「注释正文不含 `--`」的结构断言来守，
   * 它的判据直接来自 XML 规范，与环境无关。
   */
  it('结构上必须能通过 XML 的注释约束（上面一条已覆盖），这里只校验标签闭合', () => {
    // 一个弱一些但环境无关的补充：自闭合必须写成 `/>` 而不是 `>`。
    // `<rect ...>` 不闭合在 HTML 里合法、在 SVG 里非法，而 happy-dom 会容忍。
    const selfClosingVoids = SVG_SRC.match(/<(rect|circle|path|line|polygon|ellipse)\b[^>]*>/g) ?? []
    for (const tag of selfClosingVoids) {
      expect(tag.endsWith('/>')).toBe(true)
    }
  })
})

describe('favicon.svg：与 AppLogo 的 tile 变体同造型', () => {
  /**
   * 与 AppLogo.vue 保持一致的几何常量。
   *
   * <p>刻意在这里再抄一份：改了组件却忘了改 favicon（或反过来），
   * 页签与侧栏就会是两个不同的标记，而这种不一致没人会主动检查。
   */
  const RING_R = 7.6
  const STROKE = 2.9
  const DOT_R = 1.95

  it('弧的半径与 AppLogo 一致', () => {
    const d = SVG_SRC.match(/<path[^>]*\bd="([^"]+)"/)?.[1] ?? ''
    const arc = d.match(/A ([\d.]+) ([\d.]+)/)

    expect(arc).toBeTruthy()
    expect(+arc![1]).toBe(RING_R)
    expect(+arc![2]).toBe(RING_R)
  })

  it('线宽与点的半径与 AppLogo 一致', () => {
    const strokeW = SVG_SRC.match(/stroke-width="([\d.]+)"/)?.[1]
    const dotR = SVG_SRC.match(/<circle[^>]*\br="([\d.]+)"/)?.[1]

    expect(+strokeW!).toBe(STROKE)
    expect(+dotR!).toBe(DOT_R)
  })

  it('端点必须是 butt —— round 会外扩 STROKE/2，把缺口里的点糊住', () => {
    // 512px 渲染实测：缺口弧长 7.96 − cap 2.90 = 净剩 5.06，而点直径 5.00，
    // 余量仅 0.029 单位 → 点把缺口完全堵死，环读成实心圆。
    expect(SVG_SRC).toContain('stroke-linecap="butt"')
  })

  it('色值必须写死，不能引用 CSS 变量', () => {
    // favicon 被浏览器当独立文档加载，拿不到页面的 :root，
    // 写 var(--el-color-primary) 会解析失败并回落到黑色。
    expect(SVG_SRC).not.toContain('var(--')

    const stops = [...SVG_SRC.matchAll(/stop-color="([^"]+)"/g)].map((m) => m[1])
    expect(stops).toHaveLength(2)
    for (const s of stops) expect(s).toMatch(/^#[0-9a-f]{6}$/i)
  })
})

describe('index.html：引用方式', () => {
  it('必须有指向 /favicon.svg 的 icon link，且声明 svg 类型', () => {
    const link = INDEX_SRC.match(/<link[^>]*rel="icon"[^>]*>/)?.[0] ?? ''

    expect(link).toContain('/favicon.svg')
    // 不写 type 时浏览器按 URL 推断，多数情况也能work，
    // 但显式声明可以避免部分浏览器把它当 image/png 去解码。
    expect(link).toContain('image/svg+xml')
  })
})
