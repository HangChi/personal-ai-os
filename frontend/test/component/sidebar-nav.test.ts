import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * 侧栏折叠态的分组结构契约。
 *
 * <p>这里用**源码断言**而不是 mount，是经过权衡的：`App.vue` 依赖 router、
 * 多个 Pinia store、Element Plus 的 layout 组件，只为验证一条 <li> 的
 * 渲染方式去搭全套上下文，成本远高于收益；而这里要守的判据本身就是
 * 「模板里写的是哪种指令」，源码就是最直接的证据。
 *
 * <p>守的是一个**已经犯过的错**：折叠态原先用 `v-show="!navCollapsed"`
 * 把分组标签整个 display:none 掉。后果不是「少了一行字」这么轻：
 * 实测第一项 y 从 93 跳到 70（整列图标上移 23px、紧贴品牌区只剩 10px），
 * 而且两组之间再无分界，九个图标变成等距 42px 的一条直线 —— 分组信息丢失。
 * 这类退化在 type-check / lint / build 里全都看不出来。
 */
// happy-dom 环境下 `import.meta.url` 不是 file:// 协议（fileURLToPath 会抛
// "The URL must be of scheme file"），所以用 cwd 拼路径 —— vitest 从
// frontend/ 目录启动，`src/App.vue` 是稳定的相对位置。
const APP_SRC = readFileSync(resolve(process.cwd(), 'src/App.vue'), 'utf8')

describe('侧栏折叠：分组标签必须保留占位', () => {
  it('分组标签不能用 v-show 跟随折叠状态（那会把它从布局里抽走）', () => {
    const labelTag = APP_SRC.match(/<li[^>]*class="app-shell__group-label"[^>]*>/s)?.[0] ?? ''

    expect(labelTag).toBeTruthy()
    // v-show 的作用就是 display:none —— 它不只隐藏文字，连 33px 的占位一起消失。
    // 正确做法：保留 <li>、把文字换成 v-if，折叠时由 CSS 降级成一条分隔线。
    expect(labelTag).not.toContain('v-show')
  })

  it('折叠态给标签加 --divider 修饰类，并标记 aria-hidden', () => {
    const labelTag = APP_SRC.match(/<li[^>]*class="app-shell__group-label"[^>]*>/s)?.[0] ?? ''

    expect(labelTag).toContain('app-shell__group-label--divider')
    // 折叠态里这个 <li> 只剩一条装饰线，没有可读文本。
    // 不标 aria-hidden 的话，读屏会念到一个空列表项。
    expect(labelTag).toContain('aria-hidden')
  })

  it('文字用 v-if 而非 v-show —— 文本节点必须真的不存在', () => {
    // 与上面两条配套：结构保留、文字移除。v-show 会把文字留在 DOM 里，
    // 虽然视觉不可见，但读屏与 Ctrl+F 仍能找到，与「折叠态没有分组文字」矛盾。
    expect(APP_SRC).toMatch(/<span v-if="!navCollapsed">/)
  })

  it('折叠态的分隔线样式存在，且组间空档大于组内间距', () => {
    // 判据来自实测：组内相邻项间隔 42px（40 高 + 2 外边距），项间只空 2px。
    // 所以「分组」只能靠组间更大的空档表达 —— 第一版给 8px/8px 时组间空档 17px，
    // 比组内 42px 还紧，读起来是「这里塞了条线」而不是「这里换了一组」。
    const dividerBlock = APP_SRC.match(
      /\.app-shell__nav\.el-menu--collapse > \.app-shell__group-label--divider \{([^}]*)\}/,
    )?.[1]

    expect(dividerBlock).toBeTruthy()
    // 上下各 12px → 组间空档 25px = 组内 2px 的 12 倍
    expect(dividerBlock).toContain('var(--os-space-3)')
    // 高度必须是 1px 的纯占位，不能再用展开态的行高
    expect(dividerBlock).toContain('height: 1px')
  })
})
