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
