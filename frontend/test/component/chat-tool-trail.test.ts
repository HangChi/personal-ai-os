import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import ChatToolTrail from '@/components/chat/ChatToolTrail.vue'
import type { ToolInvocation } from '@/types/api'

/**
 * 工具轨迹组件的渲染契约。
 *
 * <p>选它作为第一批组件测试，是因为它踩过的那类问题**纯函数测试按定义抓不到**：
 * 汇总文案的截断规则、失败数的显示条件、`durationMs === null` 与 `0` 的区别，
 * 全都只在模板里体现。这些逻辑写错了不会抛异常，只会让界面上少一句话。
 */

function invocation(overrides: Partial<ToolInvocation> = {}): ToolInvocation {
  return {
    id: 1,
    operation: 'createTask',
    source: 'TOOL',
    outcome: 'SUCCESS',
    durationMs: 12,
    conversationId: 'c-1',
    turnId: 't-1',
    createdAt: new Date().toISOString(),
    ...overrides,
  } as ToolInvocation
}

describe('ChatToolTrail', () => {
  it('没有调用记录时整块不渲染（避免留下一根空的分隔线）', () => {
    const wrapper = mount(ChatToolTrail, { props: { items: [] } })

    expect(wrapper.find('.chat-trail').exists()).toBe(false)
  })

  it('汇总行给出条数与工具名，而不是只写「已调用 N 个」', () => {
    const wrapper = mount(ChatToolTrail, {
      props: { items: [invocation({ id: 1 }), invocation({ id: 2, operation: 'findTasks' })] },
    })

    const text = wrapper.text()
    expect(text).toContain('已调用 2 个工具')
    expect(text).toContain('createTask、findTasks')
  })

  it('超过 3 个工具时截断并标注总数，避免汇总行被撑成一整段', () => {
    const items = ['a', 'b', 'c', 'd', 'e'].map((operation, index) => invocation({ id: index + 1, operation }))

    const text = mount(ChatToolTrail, { props: { items } }).text()

    expect(text).toContain('a、b、c')
    expect(text).not.toContain('a、b、c、d')
    expect(text).toContain('等 5 项')
  })

  it('有失败时才出现失败计数（全成功时不制造噪音）', () => {
    const okOnly = mount(ChatToolTrail, { props: { items: [invocation()] } })
    expect(okOnly.text()).not.toContain('个失败')

    const withFailure = mount(ChatToolTrail, {
      props: { items: [invocation({ id: 1 }), invocation({ id: 2, outcome: 'FAILED' })] },
    })
    expect(withFailure.text()).toContain('1 个失败')
  })

  it('durationMs 为 null 显示「—」，为 0 显示 0 ms —— 待确认与真跑了 0 毫秒不是一回事', () => {
    const notExecuted = mount(ChatToolTrail, { props: { items: [invocation({ durationMs: null })] } })
    expect(notExecuted.text()).toContain('—')
    expect(notExecuted.text()).not.toContain('null')

    const zero = mount(ChatToolTrail, { props: { items: [invocation({ durationMs: 0 })] } })
    expect(zero.text()).toContain('0 ms')
  })

  it('每条记录都带状态色点，颜色由 outcome 决定', () => {
    const wrapper = mount(ChatToolTrail, {
      props: { items: [invocation({ outcome: 'FAILED' }), invocation({ id: 2, outcome: 'SUCCESS' })] },
    })

    const dots = wrapper.findAll('.chat-trail__dot')
    expect(dots).toHaveLength(2)
    expect(dots[0].classes()).toContain('chat-trail__dot--danger')
    expect(dots[1].classes()).toContain('chat-trail__dot--success')
  })
})
