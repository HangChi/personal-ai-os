import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import AppLogo from '@/components/AppLogo.vue'

/**
 * 品牌标记的几何约束与 DOM 契约。
 *
 * <p>这个文件存在的理由：logo 是**唯一无法靠肉眼验收**的部件 ——
 * 我看不到图，只能靠像素统计；而 SVG 的问题是画错了也**不报任何错**。
 * 本轮已经踩过两次，两次都表现为「DOM 结构完全正常」：
 *
 * <ol>
 *   <li>弧的起终点写反 → 配 `large-arc=1` 只扫过 60°，
 *       浏览器退化成右上角一小团白色，环整个没画出来；</li>
 *   <li>缺口 30° 配 `round` 端点 → 两端各外扩 `STROKE/2` 吃掉 2.9 单位，
 *       净剩空隙 5.06 而点直径恰好 5.00（余量 0.029 单位），
 *       点把缺口完全堵死，环读成实心圆。</li>
 * </ol>
 *
 * 所以这里断言的不是「现在长什么样」，而是**那几个一旦破坏就会静默失效的约束**。
 * 数值来源是 512px 渲染图的极坐标逐度采样与连通域分析，见 AppLogo.vue 文件头。
 */

/** 与组件保持一致的几何常量 —— 故意在这里抄一份，让改动必须两边都动 */
const VIEWBOX = 32
const RING_R = 7.6
const STROKE = 2.9
const GAP_HALF = 42
const DOT_R = 1.95

function mountLogo(props: Record<string, unknown> = {}) {
  return mount(AppLogo, { props })
}

/** 从 path 的 d 属性里抠出起终点，用来反过来校验角度 */
function parseArc(d: string) {
  const m = d.match(/^M ([\d.-]+) ([\d.-]+) A ([\d.-]+) ([\d.-]+) 0 (\d) (\d) ([\d.-]+) ([\d.-]+)$/)
  if (!m) return null
  return {
    sx: +m[1],
    sy: +m[2],
    rx: +m[3],
    ry: +m[4],
    largeArc: +m[5],
    sweep: +m[6],
    ex: +m[7],
    ey: +m[8],
  }
}

/** 把 viewBox 坐标换算成相对圆心的角度（度，屏幕坐标：0°=右，顺时针为正） */
function angleOf(x: number, y: number) {
  return (Math.atan2(y - VIEWBOX / 2, x - VIEWBOX / 2) * 180) / Math.PI
}

describe('AppLogo 几何：弧必须是 276° 那一大段', () => {
  it('弧路径能被解析，且半径与常量一致', () => {
    const wrapper = mountLogo()
    const arc = parseArc(wrapper.find('path').attributes('d') ?? '')

    expect(arc).not.toBeNull()
    expect(arc?.rx).toBe(RING_R)
    expect(arc?.ry).toBe(RING_R)
  })

  it('sweep=1 时终点必须落在「起点角度继续增大」的方向上，否则 large-arc=1 自相矛盾', () => {
    const wrapper = mountLogo()
    const arc = parseArc(wrapper.find('path').attributes('d') ?? '')!

    // 这正是第 1 次踩的坑：起点 -60°、终点 0°，sweep=1（角度增大方向）
    // 只扫过 60°，与 large-arc=1（要求 >180°）矛盾 → 浏览器画出一小团。
    // 判据：从起点角度「顺时针（角度增大）」走到终点，必须超过 180°。
    const startAngle = angleOf(arc.sx, arc.sy)
    const endAngle = angleOf(arc.ex, arc.ey)
    const clockwiseSweep = (endAngle - startAngle + 360) % 360

    expect(arc.sweep).toBe(1)
    expect(arc.largeArc).toBe(1)
    expect(clockwiseSweep).toBeGreaterThan(180)
    // 精确等于 360 - 2*GAP_HALF：缺口就是剩下那两段，不多不少
    expect(clockwiseSweep).toBeCloseTo(360 - 2 * GAP_HALF, 0)
  })

  it('两端落在 ±GAP_HALF 的缺口边界上（端点位置与缺口宽度同源）', () => {
    const wrapper = mountLogo()
    const arc = parseArc(wrapper.find('path').attributes('d') ?? '')!
    const GAP_CENTER = -30

    // 端点角度必须是 GAP_CENTER ± GAP_HALF，误差来自 toFixed(3) 的舍入。
    // 用 `||` 取模让 -72 与 288 都成立。
    const norm = (d: number) => ((d % 360) + 360) % 360
    const startAngle = norm(angleOf(arc.sx, arc.sy))
    const endAngle = norm(angleOf(arc.ex, arc.ey))

    expect(startAngle).toBeCloseTo(norm(GAP_CENTER + GAP_HALF), 1)
    expect(endAngle).toBeCloseTo(norm(GAP_CENTER - GAP_HALF), 0)
  })
})

describe('AppLogo 几何：缺口必须比点宽出可见缝隙', () => {
  /**
   * 这是第 2 次踩的坑的核心公式：缺口真正被吃掉的是**弧长**，不是角度。
   * 初版「点张力 19.2° < 缺口 60°」看着绰绰有余，实际余量只有 0.029 单位。
   */
  const gapArcLength = ((2 * GAP_HALF * Math.PI) / 180) * RING_R

  it('缺口净空（扣掉端点吃掉的部分）必须显著大于点直径', () => {
    // 端点用 butt 就不会额外吃掉缺口；用 round 则两端各外扩 STROKE/2。
    // 这里按 butt 计算，并在下一条单独守住「端点样式不是 round」。
    const freeSpace = gapArcLength

    // 判据来自实测扫描：每侧余量 ≥1.6 单位（16px 下约 0.8px）才能分成两个连通域
    expect(freeSpace - 2 * DOT_R).toBeGreaterThanOrEqual(3.2)
  })

  it('端点必须是 butt —— round 会各外扩 STROKE/2，把缺口吃回去', () => {
    const wrapper = mountLogo()

    // round cap 共吃掉 STROKE = 2.9 单位，这正是初版让「环+点」糊成一团的直接原因。
    // 实测 5 组参数 × 6 个尺寸：round 端点在 16px 下**无一通过**（见 AppLogo.vue 文件头）。
    expect(wrapper.find('path').attributes('stroke-linecap')).toBe('butt')
  })

  it('点半径不能放大到 2.5（初版值，会把缺口堵死）', () => {
    const wrapper = mountLogo()
    const dotR = +(wrapper.find('circle').attributes('r') ?? '0')

    expect(dotR).toBe(DOT_R)
    expect(dotR).toBeLessThan(2.5)
    // 缺口的净空必须真的容得下这个点，且两侧各留 1.6 单位以上
    expect((gapArcLength - 2 * dotR) / 2).toBeGreaterThanOrEqual(1.6)
  })

  it('标记整体不超出 32 的方块（环外沿 + 描边一半）', () => {
    // 这条守的是「改 R 或 STROKE 时忘了看边距」——
    // 越界不会报错，只会让标志贴边或被裁掉一角。
    const outerEdge = RING_R + STROKE / 2

    expect(outerEdge).toBeLessThan(VIEWBOX / 2)
    // 还要给「点向外凸出」留余量：点最外沿 = RING_R + DOT_R
    expect(RING_R + DOT_R).toBeLessThan(VIEWBOX / 2)
  })
})

describe('AppLogo 两个变体：底色与前景色的分工', () => {
  it('tile 用渐变底 + 白色标记，渐变两端写死亮色主色值', () => {
    const wrapper = mountLogo({ variant: 'tile' })
    const stops = wrapper.findAll('linearGradient stop')

    expect(wrapper.find('rect').exists()).toBe(true)
    expect(stops).toHaveLength(2)
    // ⚠️ 这里刻意**不**是 var(--el-color-primary)：暗色主题里那个阶梯方向是反的，
    // --el-color-primary-dark-2 实际更亮（#a5aef9），白标记对比度会掉到 2.10。
    // 品牌底不跟主题走，两处色值必须与 public/favicon.svg 一致。
    expect(stops[0].attributes('stop-color')).toBe('#4f46e5')
    expect(stops[1].attributes('stop-color')).toBe('#3f38b7')
    expect(wrapper.find('path').attributes('stroke')).toBe('#fff')
  })

  it('白标记与渐变两端的对比度都过 WCAG AA 图形线（≥3.0）', () => {
    // 这条守的正是暗色下真实踩到的坑：引用主色阶梯时最亮端只有 2.10。
    // 对比度按 WCAG 2.x 相对亮度公式算，不依赖渲染环境。
    const relLuminance = (hex: string) => {
      const v = hex.replace('#', '')
      const ch = [0, 2, 4].map((i) => {
        const c = parseInt(v.slice(i, i + 2), 16) / 255
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]
    }
    const contrast = (a: string, b: string) => {
      const [la, lb] = [relLuminance(a), relLuminance(b)].sort((x, y) => y - x)
      return (la + 0.05) / (lb + 0.05)
    }

    const wrapper = mountLogo({ variant: 'tile' })
    const stops = wrapper.findAll('linearGradient stop').map((s) => s.attributes('stop-color'))

    expect(contrast('#ffffff', stops[0]).toFixed(2)).toBe('6.29')
    expect(contrast('#ffffff', stops[1]).toFixed(2)).toBe('8.53')
    for (const s of stops) {
      // 3.0 是 WCAG 对「非文本的图形与界面组件」的门槛
      expect(contrast('#ffffff', s)).toBeGreaterThanOrEqual(3)
    }
  })

  it('mark 用 currentColor，能跟随任何容器的文字色', () => {
    const wrapper = mountLogo({ variant: 'mark' })

    // mark 是给消息头像用的：头像底已经定了主色浅底，
    // 只有 currentColor 能让标记自动取到同一个主色，不必再传一个 color prop。
    expect(wrapper.find('rect').exists()).toBe(false)
    expect(wrapper.find('path').attributes('stroke')).toBe('currentColor')
    expect(wrapper.find('circle').attributes('fill')).toBe('currentColor')
  })

  it('两个变体都带 aria-label，纯装饰之外仍能被读屏识别', () => {
    for (const variant of ['tile', 'mark'] as const) {
      const wrapper = mountLogo({ variant })
      expect(wrapper.attributes('role')).toBe('img')
      expect(wrapper.attributes('aria-label')).toBe('Personal AI OS')
    }
  })

  it('尺寸走 size prop，宽高严格等于它', () => {
    const wrapper = mountLogo({ size: 22 })

    // 头像里必须显式给尺寸：SVG 默认按 300x150 或撑满容器，
    // 在 flex 父容器里会变成一个巨大的方块。
    expect(wrapper.attributes('width')).toBe('22')
    expect(wrapper.attributes('height')).toBe('22')
  })
})

describe('AppLogo 渐变 id：多实例不能撞', () => {
  it('两个同尺寸实例的渐变 id 不同，且各自 url(#...) 指向自己', () => {
    // 这条守的是一个**只在特定组合下才炸**的坑：id 曾用 size 拼接，
    // 而侧栏与登录页顶栏都是 size=30 —— 靠「两者是互斥路由」才没撞。
    // 一旦同页出现两个 30px 的 logo（或做 SSR 同时渲染），就会有重复 id：
    // 浏览器只认第一个，第二处静默指到别人的渐变上。
    // 因为两处本来就是同色系，肉眼几乎看不出，属于典型的「看起来正常」。
    const host = document.createElement('div')
    document.body.appendChild(host)
    const a = mount(AppLogo, { props: { size: 30 }, attachTo: host })
    const b = mount(AppLogo, { props: { size: 30 }, attachTo: host })

    const idA = a.find('linearGradient').attributes('id')
    const idB = b.find('linearGradient').attributes('id')

    expect(idA).toBeTruthy()
    expect(idB).toBeTruthy()
    expect(idA).not.toBe(idB)
    // 引用必须指向各自的 id，而不是一个写死的值
    expect(a.find('rect').attributes('fill')).toBe(`url(#${idA})`)
    expect(b.find('rect').attributes('fill')).toBe(`url(#${idB})`)

    a.unmount()
    b.unmount()
    document.body.removeChild(host)
  })
})
