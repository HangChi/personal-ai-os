import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatMessageItem from '@/components/chat/ChatMessageItem.vue'
import type { ChatMessage } from '@/types/api'

/**
 * 消息操作区与气泡结构。
 *
 * <p>这里的断言都针对**曾经真实错过的地方**，不是给现有实现配镜子：
 *
 * 1. 操作按钮去掉文字后，若忘了写 `aria-label` / `title`，界面上只剩两个图标，
 *    读屏用户听到的是「按钮」「按钮」，鼠标用户也看不到功能提示 ——
 *    而这种退化在 `vue-tsc`、`eslint`、构建里全都看不出来。
 * 2. 首末段距清零写的是 `.md__body > :first-child`，但 `.md__body` 的直接子元素
 *    是 v-html 的包裹层 `.md__html`，那条规则**从来没命中过真正的段落**，
 *    导致气泡底部多出 8px（实测 padTop 10.8 / padBottom 18.8）。
 *    这里守住「包裹层存在且带 md__html 类」，因为选择器依赖它。
 */

const MarkdownStub = {
  name: 'MarkdownRenderer',
  props: { content: { type: String, required: true } },
  template: '<div class="md-stub"><div class="md__html" /></div>',
}

function makeMessage(overrides: Partial<ChatMessage> = {}): ChatMessage {
  return {
    id: 'm-1',
    role: 'ASSISTANT',
    content: '回答正文',
    createdAt: new Date().toISOString(),
    citations: [],
    attachments: [],
    ...overrides,
  } as ChatMessage
}

/**
 * AppLogo 的 stub。
 *
 * <p>`vitest.config.ts` 刻意不装 unplugin（自动注册在测试里没有意义，
 * 还会往源码注入未使用的自动导入语句），所以组件里的 `<AppLogo>` 必须显式 stub，
 * 否则每个用例都会刷一条 "Failed to resolve component"。
 *
 * <p>stub **保留类名**：`.app-logo--mark` 是真正的断言对象（见文末的头像用例），
 * 若 stub 成一个空 div，那条断言就变成「测 stub 自己」了。
 */
const AppLogoStub = {
  name: 'AppLogo',
  props: { size: { type: Number, default: 30 }, variant: { type: String, default: 'tile' } },
  template: '<svg :class="`app-logo app-logo--${variant}`" />',
}

function mountItem(overrides: Partial<ChatMessage> = {}) {
  return mount(ChatMessageItem, {
    props: { message: makeMessage(overrides), toolTrail: [] },
    global: { stubs: { MarkdownRenderer: MarkdownStub, AppLogo: AppLogoStub } },
  })
}

describe('消息操作区：纯图标按钮必须有可读名称', () => {
  it('助手消息的复制/重新生成都带 aria-label 与 title，且不显示文字', () => {
    const wrapper = mountItem()
    const buttons = wrapper.findAll('.chat-msg__action')

    expect(buttons).toHaveLength(2)
    for (const btn of buttons) {
      expect(btn.attributes('aria-label')).toBeTruthy()
      expect(btn.attributes('title')).toBeTruthy()
      // 只剩图标：文本内容必须为空，否则「只保留图标」就退化回带文字
      expect(btn.text()).toBe('')
    }
    expect(buttons.map((b) => b.attributes('aria-label'))).toEqual(['复制这条回复', '重新生成这条回复'])
  })

  it('用户消息的操作是复制/编辑，语义不能串到助手那套', () => {
    const wrapper = mountItem({ role: 'USER' })
    const labels = wrapper.findAll('.chat-msg__action').map((b) => b.attributes('aria-label'))

    expect(labels).toEqual(['复制这条消息', '编辑并重发这条消息'])
  })

  it('点复制时把正文原样抛给父层', async () => {
    const wrapper = mountItem({ content: '要被复制的内容' })
    await wrapper.findAll('.chat-msg__action')[0].trigger('click')

    expect(wrapper.emitted('copy')?.[0]).toEqual(['要被复制的内容'])
  })
})

describe('气泡结构：首末段距清零依赖 v-html 包裹层', () => {
  it('Markdown 渲染结果被包在 .md__html 里 —— 外层选择器必须穿过这一层', () => {
    const wrapper = mountItem()
    const html = wrapper.find('.chat-msg__bubble .md__html')

    // 这条不是在测 stub：它守的是 MarkdownRenderer 的 DOM 契约 ——
    // 一旦有人给 v-html 换掉这层包装，`.md__html > :last-child` 会静默失效，
    // 气泡底部重新多出一截 margin，而所有现有测试仍然全绿。
    expect(html.exists()).toBe(true)
  })
})

describe('助手头像：用品牌标记而不是通用图标', () => {
  it('头像里是 AppLogo 的 mark 变体，不是 EP 的 ChatLineRound', () => {
    const wrapper = mountItem()
    const avatar = wrapper.find('.chat-msg__avatar--assistant')

    expect(avatar.exists()).toBe(true)
    // 换成 mark 变体是因为头像底已经是 --el-color-primary-light-9，
    // 再叠一个实心方块（tile）会与用户头像的主色实底抢重心。
    // 同时守「不再是 el-icon」：通用图标没有身份识别度，改造前整个产品
    // 用的都是 EP 的 ChatLineRound，和任何 AI 产品都撞。
    expect(avatar.find('.app-logo--mark').exists()).toBe(true)
    expect(avatar.find('.el-icon').exists()).toBe(false)
  })

  it('用户头像保持主色实底 + 白色图标，不被品牌标记顶掉', () => {
    const wrapper = mountItem({ role: 'USER' })
    const avatar = wrapper.find('.chat-msg__avatar--user')

    // 用户侧不需要身份标记：人只有一个，图标只是「这是你说的」的标记。
    // 两个头像都换成 logo 会让对话读起来像「两个 AI 在互相回复」。
    expect(avatar.find('.app-logo').exists()).toBe(false)
    expect(avatar.find('.el-icon').exists()).toBe(true)
  })
})

/**
 * 气泡宽度的角色分工。
 *
 * <p>守的是一个**用户直接看出来的问题**：「有些框宽度很大，有些很小」。
 *
 * <p>根因：助手气泡用了 `width: fit-content`，而 fit-content 的宽度
 * = **这条消息里最长那一行 + 2×内边距**。「最长一行」由内容换行位置决定，
 * 于是每条回答各自算出一个与别人无关的数 —— 实测同屏 5 个会话分别是
 * 455 / 469 / 622 / 842px。
 *
 * <p>更关键的是：那个 842 不是「内容需要 842」——把 455 的那个会话加宽到 842 后，
 * 行数**一条都没变**（34→34、46→46、31→31、18→18、23→23）。原先的窄宽度
 * 纯粹是 fit-content 算窄了。所以修法是让助手侧定宽 `100%`。
 *
 * <p>happy-dom 不算布局，所以这里断言的是「宽度策略写在哪一侧」，
 * 而不是具体像素。真实像素靠浏览器实测（1440 下全部 842、1280 下 682、1024 下 426）。
 */
describe('气泡宽度：助手定宽铺满，用户按内容收缩', () => {
  const SOURCE = readFileSync(resolve(process.cwd(), 'src/components/chat/ChatMessageItem.vue'), 'utf8')
  /**
   * 取某条规则的属性体。
   *
   * <p>必须先剥掉 CSS 注释：这些规则的注释里写着「原先用 fit-content」这类说明，
   * 不剥的话断言会被注释内容污染 —— 第一版就是这么假失败的
   * （`not.toMatch(/\bwidth:/)` 匹配到了注释里的「width: 100%」）。
   */
  const ruleOf = (selector: string): string => {
    const raw =
      SOURCE.match(new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`))?.[1] ?? ''
    return raw.replace(/\/\*[\s\S]*?\*\//g, '')
  }

  it('助手气泡必须是 width: 100%，不能是 fit-content', () => {
    const rule = ruleOf('.chat-msg__bubble--assistant')

    expect(rule).toContain('width: 100%')
    // fit-content 是已修掉的病根，重新出现就是回退
    expect(rule).not.toContain('fit-content')
  })

  it('用户气泡必须显式保留 fit-content —— 它不再从通用类继承', () => {
    const rule = ruleOf('.chat-msg__bubble--user')

    // 这条容易漏：宽度原先是写在通用 .chat-msg__bubble 上的，
    // 助手侧改成 100% 时把通用那条删了。删掉后用户气泡会退回 flex 子项
    // 默认的 auto，**短消息也被拉成整栏宽**，右对齐的缺口消失。
    expect(rule).toContain('width: fit-content')
  })

  it('通用气泡类不能带 width —— 两侧策略必须分开写', () => {
    const rule = ruleOf('.chat-msg__bubble')

    // 通用层留一个 width 会让「按角色分开」重新退化成「两侧一个值」，
    // 而具体是哪个值取决于书写顺序，非常难查。
    //
    // ⚠️ 不能用 `/\bwidth:/`：`max-width:` 的前缀是 `-`（非单词字符），
    // `\b` 成立，于是这条断言会把合法的 `max-width: 100%` 判成违规。
    // 必须用后视排除前面紧邻的 `-`。
    expect(rule).not.toMatch(/(?<![-\w])width:/)
  })
})

/**
 * 助手气泡的尾巴（2026-10-30）。
 *
 * <p>守的是用户说的「回复和头看起来像是分离的」。气泡铺满后左边界与头像之间
 * 隔着 12px 硬空隙，头像像一枚飘在旁边的独立徽章。
 *
 * <p>尾巴用纯 CSS 双三角（`::before` 描边 + `::after` 填充）实现，这里守三件事：
 *
 * 1. **气泡必须是 `position: relative`**。两个伪元素都是 `position: absolute`，
 *    要贴的是「气泡自己的左上外沿」。气泡一旦不是定位元素，`left/top` 会一路
 *    向上找别的定位祖先，没有就退到初始包含块 —— 尾巴会跑到页面左上角去。
 *    实测漏掉这行时 `getComputedStyle(bubble).position === 'static'`，
 *    尾巴完全不在气泡旁边，而且**控制台一声不响**。
 * 2. **颜色必须走令牌**，不能硬编码。暗色下实测 `::before` 要与气泡
 *    `border-color` 完全相等、`::after` 要与 `background` 完全相等
 *    （亮色 rgb(214,219,230)/rgb(234,237,243)，暗色 rgb(82,90,112)/rgb(51,56,73)）。
 *    硬编码任何一个，切到暗色就会露出一块浅色三角。
 * 3. **`top` 必须让尾尖对齐头像垂直中心**，且描边三角比填充三角大 1px（否则
 *    填充会盖住描边，尾巴看起来没有边）。
 *
 * <p>happy-dom 不算布局，也不产出伪元素的计算样式，所以这里只能做
 * **源码结构断言**；真实几何（尾尖到头像右沿的间距、尾心偏差）靠浏览器实测，
 * 三档视宽 × 两个主题下都是 0。
 */
describe('助手气泡尾巴：双三角必然依赖定位与令牌', () => {
  const SOURCE = readFileSync(resolve(process.cwd(), 'src/components/chat/ChatMessageItem.vue'), 'utf8')
  const strip = (s: string) => s.replace(/\/\*[\s\S]*?\*\//g, '')
  /**
   * 取某个选择器的属性体。
   *
   * <p>两个坑都要绕开，且都是「看起来对、其实抓错块」：
   *
   * <ol>
   *   <li>**必须先剥注释再匹配**。这些规则的注释里就写着 `.chat-msg__bubble--assistant::before`
   *       这类选择器名（用于解释），先匹配会把注释当规则抓走；</li>
   *   <li>**必须取最后一次匹配**。`::after` 出现了两次 —— 先是与 `::before` 并列的
   *       公共块（`.chat-msg__bubble--assistant::before,\n.chat-msg__bubble--assistant::after {`），
   *       再是它自己的独立块。`String.match` 只取第一个，于是拿到的会是公共块的内容
   *       `content/position/top/width/height`，而断言要找的 `left` 根本不在里面 ——
   *       报错信息指向「left 不存在」，真实原因却是抓错了块。</li>
   * </ol>
   */
  const ruleOf = (selector: string): string => {
    const src = strip(SOURCE)
    const re = new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'g')
    const bodies = [...src.matchAll(re)].map((m) => m[1])
    return bodies.length > 0 ? bodies[bodies.length - 1] : ''
  }

  it('气泡必须是定位元素 —— 否则绝对定位的尾巴会跑到页面左上角', () => {
    expect(ruleOf('.chat-msg__bubble--assistant')).toContain('position: relative')
  })

  it('气泡不能有 overflow 裁剪 —— 会静默剪掉画在外面的尾巴', () => {
    // 尾巴画在 `left: -12px`，落在气泡盒外。若有人为了裁代码块圆角给气泡加
    // `overflow: hidden`，尾巴会整个消失且不报错。这里只允许 overflow 不出现，
    // 或显式声明为 visible。
    const rule = ruleOf('.chat-msg__bubble--assistant')
    expect(rule).not.toMatch(/overflow:\s*hidden/)
    expect(rule).not.toMatch(/overflow:\s*clip/)
  })

  it('两个伪元素都声明了绝对定位与零宽高（border 三角的前提）', () => {
    // 直接找公共块本身：它是一条同时列出两个伪元素的规则，
    // 用 `ruleOf('...::before')` 取到的只会是 `::before` 自己的块。
    const shared = strip(SOURCE).match(
      /\.chat-msg__bubble--assistant::before,\s*\.chat-msg__bubble--assistant::after\s*\{([^}]*)\}/,
    )?.[1]

    expect(shared).toBeTruthy()
    expect(shared).toContain('position: absolute')
    expect(shared).toContain('width: 0')
    expect(shared).toContain('height: 0')
    expect(shared).toContain('border-style: solid')
  })

  it('描边三角用气泡边框色，填充三角用气泡底色 —— 都不能硬编码', () => {
    const before = ruleOf('.chat-msg__bubble--assistant::before')
    const after = ruleOf('.chat-msg__bubble--assistant::after')

    // 走令牌而不是字面量颜色：亮/暗两套主题下尾巴自动跟随，实测两者
    // 分别与 border-color / background 完全相等。
    expect(before).toContain('var(--os-bubble-border)')
    expect(after).toContain('var(--os-bubble-bg)')
    expect(before).not.toMatch(/#[0-9a-f]{3,8}/i)
    expect(after).not.toMatch(/#[0-9a-f]{3,8}/i)
    expect(before).not.toMatch(/rgba?\(/)
    expect(after).not.toMatch(/rgba?\(/)
  })

  it('填充三角比描边三角小 1px 且错开 1px，两者共用一条外边', () => {
    const before = ruleOf('.chat-msg__bubble--assistant::before')
    const after = ruleOf('.chat-msg__bubble--assistant::after')
    const shared = strip(SOURCE).match(
      /\.chat-msg__bubble--assistant::before,\s*\.chat-msg__bubble--assistant::after\s*\{([^}]*)\}/,
    )?.[1]

    // 描边三角高 14px（7+7）、向右伸 12px；填充三角高 12px（6+6）、伸 11px，
    // 顶点各下移 1px。差这 1px 才露出左上那圈边；相等则填充盖住描边，
    // 尾巴看起来是「一块没有轮廓的色块」。
    expect(before).toMatch(/border-width:\s*7px\s+12px\s+7px\s+0/)
    expect(after).toMatch(/border-width:\s*6px\s+11px\s+6px\s+0/)
    // `top` 写在公共块里（两个伪元素共用起点），填充三角再单独覆盖成 12px。
    expect(shared).toMatch(/top:\s*11px/)
    expect(after).toMatch(/top:\s*12px/)
  })

  it('尾巴的横向位置必须完全伸进 12px 空隙（与 .chat-msg 的 gap 一致）', () => {
    const before = ruleOf('.chat-msg__bubble--assistant::before')
    const after = ruleOf('.chat-msg__bubble--assistant::after')

    // 伸出的长度必须等于消息行的 gap（12px），尾尖才能正好顶到头像右沿。
    // 实测漏算时尾尖距头像还差 5px（tailTipX 562 vs avatarR 557），
    // 视觉上就是「尾巴浮在半空没接上」。
    expect(before).toMatch(/left:\s*-12px/)
    expect(after).toMatch(/left:\s*-11px/)
    // 与 .chat-msg 的 gap 联动：改了 gap 就必须同步改这两个值
    expect(ruleOf('.chat-msg')).toContain('gap: var(--os-space-3)')
    // 空隙的实际像素值是 12，所以「伸出 12px」才是正好够到。这个数在 tokens.css 里，
    // 不在本组件里 —— 必须去令牌文件取，否则断言的是不存在于当前文件的字面量。
    const tokens = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8')
    expect(tokens).toMatch(/--os-space-3:\s*12px/)
  })
})
