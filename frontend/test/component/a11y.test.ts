import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatComposer from '@/components/chat/ChatComposer.vue'
import ChatStreamingItem from '@/components/chat/ChatStreamingItem.vue'
import type { ContextUsage } from '@/api/chat'

/**
 * 无障碍契约（P3）。
 *
 * <p>集中在一个文件而不是散到各组件测试里，是因为这里检验的是**跨组件的一致性规则**：
 * 纯图标控件必须有可读名称、开关必须暴露状态、纯视觉动画必须有文字替代。
 * 分散写容易每个组件各写一套，漏掉的那个也没人发现。
 *
 * <p>这些属性没有任何视觉表现 —— 删掉不会有人察觉，只有读屏用户会发现某个按钮
 * 变成了「按钮」两个字。所以必须有测试盯着。
 */

const contextUsage: ContextUsage = {
  promptTokens: 0,
  budgetTokens: 0,
  contextWindow: 0,
  estimated: false,
}

function composerProps(overrides: Record<string, unknown> = {}) {
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

describe('无障碍契约', () => {
  it('知识库开关用 aria-pressed 表达开关状态，而不是只靠颜色和 title', async () => {
    const off = mount(ChatComposer, { props: composerProps({ knowledgeOn: false }) })
    expect(off.find('[aria-pressed="false"]').exists()).toBe(true)

    const on = mount(ChatComposer, { props: composerProps({ knowledgeOn: true }) })
    expect(on.find('[aria-pressed="true"]').exists()).toBe(true)
  })

  it('思考档位是纯图标按钮，必须有可读的 aria-label（含当前档位）', () => {
    const wrapper = mount(ChatComposer, { props: composerProps({ thinkingLevel: 'deep' }) })

    const label = wrapper.find('.chat-composer__thinking-trigger').attributes('aria-label')
    expect(label).toBeTruthy()
    expect(label).toContain('思考程度')
    expect(label).toContain('深度')
  })

  it('流式占位提供可读状态，三点动画不再是唯一反馈', () => {
    const wrapper = mount(ChatStreamingItem, {
      props: { content: '', hint: '' },
      global: { stubs: { AppLogo: true } },
    })

    const status = wrapper.get('[role="status"]')
    expect(status.text()).toBe('正在回复')
    expect(status.classes()).not.toContain('sr-only')
    wrapper.unmount()
  })

  it('生成正文期间仍保留状态，读屏可以识别检索阶段', () => {
    const wrapper = mount(ChatStreamingItem, {
      props: { content: '正文内容', hint: '正在检索知识库…' },
      global: { stubs: { AppLogo: true, MarkdownRenderer: true } },
    })

    expect(wrapper.get('[role="status"]').text()).toBe('正在检索知识库…')
    wrapper.unmount()
  })
})
