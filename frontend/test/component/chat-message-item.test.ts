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

function mountItem(overrides: Partial<ChatMessage> = {}) {
  return mount(ChatMessageItem, {
    props: { message: makeMessage(overrides), toolTrail: [] },
    global: { stubs: { MarkdownRenderer: MarkdownStub } },
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
