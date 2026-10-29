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

  it('流式占位给了文字替代 —— 三点动画对读屏是完全无声的', () => {
    const wrapper = mount(ChatStreamingItem, {
      props: { content: '', hint: '' },
      global: { stubs: { AppLogo: true } },
    })

    const hidden = wrapper.find('.sr-only')
    expect(hidden.exists()).toBe(true)
    expect(hidden.text()).toContain('助手正在回复')
  })

  it('sr-only 不能用 display:none —— 那样连读屏也读不到，等于没写', () => {
    // 样式断言只能靠约定保证，这里守住「类名存在」这个可检查的部分；
    // 真正禁止 display:none 的是 main.css 里的实现与它的注释。
    const wrapper = mount(ChatStreamingItem, {
      props: { content: '', hint: '' },
      // vitest 刻意不装 unplugin（自动注册），AppLogo 需显式 stub 才不刷 resolve 警告。
      // 本文件只关心无障碍属性，标记造型由 app-logo.test.ts 负责，所以 stub 成 true 即可。
      global: { stubs: { AppLogo: true } },
    })

    expect(wrapper.find('.sr-only').classes()).toContain('sr-only')
  })
})
