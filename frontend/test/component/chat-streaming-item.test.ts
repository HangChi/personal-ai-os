import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'

import ChatStreamingItem from '@/components/chat/ChatStreamingItem.vue'

/**
 * 流式渲染的**节流接线**。
 *
 * <p>这个组件写过一次「节流写了但没人用」的事故：`renderedContent` 被正常赋值、
 * 节流器被正常调用，但模板绑的还是原始 `content` —— 于是每个 SSE delta 都跑一遍
 * `marked.parse` + `DOMPurify.sanitize` + 整块 v-html 重写，节流形同虚设。
 * 而这个错误 `vue-tsc`、`eslint`、纯函数测试全部通过（ref 被写就算「已使用」）。
 *
 * <p>所以断言必须落在「子组件实际收到了什么 prop」上，而不是「节流器被调用了几次」。
 * 这里把 MarkdownRenderer 换成只暴露 content 长度的桩，避免把 marked/hljs 拖进测试。
 */
const MarkdownStub = {
  name: 'MarkdownRenderer',
  props: { content: { type: String, required: true }, streaming: { type: Boolean, default: false } },
  template: '<div class="md-stub" :data-content="content" />',
}

function mountWith(content: string) {
  return mount(ChatStreamingItem, {
    props: { content, hint: null },
    global: { stubs: { MarkdownRenderer: MarkdownStub } },
  })
}

function renderedContent(wrapper: ReturnType<typeof mountWith>): string {
  return wrapper.find('.md-stub').attributes('data-content') ?? ''
}

describe('ChatStreamingItem 渲染节流', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('首帧立即渲染：第一个 token 不该先干等一个节流窗口', () => {
    vi.useFakeTimers()
    const wrapper = mountWith('你好')

    expect(renderedContent(wrapper)).toBe('你好')
  })

  it('窗口内的连续 delta 被合并：子组件不会收到每一个中间值', async () => {
    vi.useFakeTimers()
    const wrapper = mountWith('a')

    // 同一节流窗口内连推三次。若节流失效（模板绑原始 content），
    // 这里会立刻变成 "abcd" —— 正是本次修复要守住的行为。
    await wrapper.setProps({ content: 'ab' })
    await wrapper.setProps({ content: 'abc' })
    await wrapper.setProps({ content: 'abcd' })
    await nextTick()

    expect(renderedContent(wrapper)).not.toBe('abcd')
  })

  it('窗口结束后落地最后一帧：不能把尾巴吞掉（看起来像回答被截断）', async () => {
    vi.useFakeTimers()
    const wrapper = mountWith('a')

    await wrapper.setProps({ content: 'ab' })
    await wrapper.setProps({ content: 'abc' })
    vi.advanceTimersByTime(200)
    await nextTick()

    expect(renderedContent(wrapper)).toBe('abc')
  })

  it('未收到首 token 时显示打字动画而不是空的 Markdown 容器', () => {
    const wrapper = mountWith('')

    expect(wrapper.find('.chat-streaming__typing').exists()).toBe(true)
    expect(wrapper.find('.md-stub').exists()).toBe(false)
  })
})
