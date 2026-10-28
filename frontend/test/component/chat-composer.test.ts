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
    // 模型选择器与读数组都在第一行；不传会触发三条 required prop 警告，
    // 让人误以为输入区坏了（实测过：警告满屏，真正的失败反而被淹没）
    sessionModel: null,
    chatModels: ['qwen3.8-flash'],
    defaultModel: 'qwen3.8-flash',
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

  /*
   * 第一行是「一行读数」：模型名 + 字数 + 上下文。
   *
   * <p>这三条断言守的是**布局契约**而不是视觉效果 —— 改造前模型是一个
   * 32px 的表单框、字数是 15px 的文本、上下文在页面另一端，用户的原话是
   * 「样式还是太丑了」。判据因此是「三者是否在同一容器里、字数分母是否
   * 与输入框的 maxlength 同源」，而不是任何主观描述。
   */
  it('第一行同时呈现模型名与字数（同属 readouts 行）', () => {
    const wrapper = mount(ChatComposer, {
      props: baseProps({ modelValue: '12345', contextUsage: { ...contextUsage, contextWindow: 32768 } }),
    })

    const metaRow = wrapper.find('.chat-composer__meta-row')
    expect(metaRow.exists()).toBe(true)
    // 模型名回显在 meta-row 内，不是工具行
    expect(metaRow.text()).toContain('qwen3.8-flash')
    // 字数分母必须与 textarea 的 maxlength 一致，否则「还能打多少」是错的
    const maxlength = wrapper.find('textarea').attributes('maxlength')
    expect(metaRow.find('.chat-composer__count').text()).toBe(`5 / ${maxlength}`)
  })

  it('budgetTokens=0 时不渲染上下文读数（未产生用量时不留空壳）', () => {
    const wrapper = mount(ChatComposer, { props: baseProps() })

    expect(wrapper.find('.context-meter').exists()).toBe(false)
  })

  it('有预算时上下文读数与字数同排，且改预算会冒泡到父级', async () => {
    const usage: ContextUsage = { promptTokens: 4096, budgetTokens: 32768, contextWindow: 32768, estimated: false }
    const wrapper = mount(ChatComposer, { props: baseProps({ contextUsage: usage }) })

    const readouts = wrapper.find('.chat-composer__readouts')
    expect(readouts.find('.context-meter').exists()).toBe(true)
    // 24px 热区、12px 字号：与字数同一规格，这是「不再一大两小」的判据
    expect(readouts.find('.chat-composer__count').exists()).toBe(true)
  })
})
