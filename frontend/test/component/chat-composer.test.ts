import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import type { ContextUsage } from '@/api/chat'

/**
 * 输入区的交互契约。
 *
 * <p>选它做组件测试，是因为它集中了几条**只在真实按键事件下才成立**的规则：
 * 输入法选词期间不能发送、Shift+Enter 换行、两种 Enter 偏好，
 * 以及「正在生成」时按钮必须变成停止。这些规则纯函数测试完全覆盖不到，
 * 而写错的表现是「打中文时消息被吞」或「想换行却发出去了」—— 用户会立刻感知。
 */

/** budgetTokens 为 0 时不渲染仪表盘，多数用例不需要它 */
const contextUsage: ContextUsage = {
  promptTokens: 0,
  budgetTokens: 0,
  contextWindow: 0,
  estimated: false,
}

function baseProps(overrides: Record<string, unknown> = {}) {
  return {
    modelValue: '',
    streaming: false,
    knowledgeOn: false,
    topK: 4,
    thinkingLevel: 'off' as const,
    enterSend: true,
    contextUsage,
    ...overrides,
  }
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text().includes(text))
}

describe('ChatComposer', () => {
  it('正在生成时按钮变成「停止」，点击只发 stop', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ streaming: true, modelValue: '内容' }) })

    expect(buttonByText(wrapper, '停止')).toBeTruthy()
    expect(buttonByText(wrapper, '发送')).toBeFalsy()

    await buttonByText(wrapper, '停止')!.trigger('click')

    expect(wrapper.emitted('stop')).toHaveLength(1)
    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('输入为空时发送按钮不可点（避免发出空消息）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '   ' }) })

    expect(buttonByText(wrapper, '发送')!.attributes('disabled')).toBeDefined()
  })

  it('点击发送会带上当前附件列表（无附件时为空数组）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    await buttonByText(wrapper, '发送')!.trigger('click')

    expect(wrapper.emitted('send')?.[0]).toEqual([[]])
  })

  it('enterSend=true：Enter 发送，Shift+Enter 换行不发送', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })
    const textarea = wrapper.find('textarea')

    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    expect(wrapper.emitted('send')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('enterSend=false：Enter 不发送，Ctrl/Cmd+Enter 才发送', async () => {
    const wrapper = mount(ChatComposer, {
      props: baseProps({ modelValue: '你好', enterSend: false }),
    })
    const textarea = wrapper.find('textarea')

    await textarea.trigger('keydown', { key: 'Enter' })
    expect(wrapper.emitted('send')).toBeUndefined()

    await textarea.trigger('keydown', { key: 'Enter', ctrlKey: true })
    expect(wrapper.emitted('send')).toHaveLength(1)
  })

  it('输入法选词期间的 Enter 被忽略（否则打中文时按回车会把消息发出去）', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    // isComposing 由浏览器在 composition 期间置位，模拟时必须显式带上
    await wrapper.find('textarea').trigger('keydown', { key: 'Enter', isComposing: true })

    expect(wrapper.emitted('send')).toBeUndefined()
  })

  it('其他按键不触发发送', async () => {
    const wrapper = mount(ChatComposer, { props: baseProps({ modelValue: '你好' }) })

    await wrapper.find('textarea').trigger('keydown', { key: 'a' })

    expect(wrapper.emitted('send')).toBeUndefined()
  })
})
