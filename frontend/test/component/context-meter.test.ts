import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ContextMeter from '@/components/chat/ContextMeter.vue'

describe('ContextMeter', () => {
  it('区分估算用量、预算和模型上限，并可读取精确 token 值', () => {
    const wrapper = mount(ContextMeter, {
      props: {
        usage: {
          promptTokens: 4096,
          budgetTokens: 32768,
          contextWindow: 128000,
          estimated: true,
          modelName: 'custom-model',
          windowSource: 'configured',
        },
      },
    })
    expect(wrapper.find('.context-meter__value').text()).toBe('≈4K / 32K')
    expect(wrapper.find('.context-meter__limit').text()).toBe('上限 125K')
    const title = wrapper.find('button').attributes('title')
    expect(title).toContain('custom-model')
    expect(title).toContain('128,000 token')
    expect(title).toContain('模型配置')
  })
  it('默认保护值显示窗口未知，不能伪装成模型能力', () => {
    const wrapper = mount(ContextMeter, {
      props: {
        usage: {
          promptTokens: 100,
          budgetTokens: 32768,
          contextWindow: 131072,
          estimated: false,
          modelName: 'unknown',
          windowSource: 'fallback',
        },
      },
    })
    expect(wrapper.find('.context-meter__limit').text()).toBe('窗口未知')
    expect(wrapper.find('.context-meter__value').text()).toBe('100 / 32K')
    expect(wrapper.find('button').attributes('title')).toContain('保护性默认预算')
  })

  it('说明回复预留量与安全余量', () => {
    const wrapper = mount(ContextMeter, {
      props: {
        usage: {
          promptTokens: 100,
          budgetTokens: 32768,
          contextWindow: 131072,
          estimated: true,
          inputLimitTokens: 126464,
          outputReserveTokens: 4096,
          safetyMarginTokens: 512,
          modelName: 'custom',
          windowSource: 'configured',
        },
      },
    })
    expect(wrapper.find('button').attributes('title')).toContain('预留回复：4,096 token')
    expect(wrapper.find('button').attributes('title')).toContain('安全余量：512 token')
  })
  it('成功用量只标为输入下限，端点声明与配置窗口分别显示', () => {
    const wrapper = mount(ContextMeter, {
      props: {
        usage: {
          promptTokens: 4096,
          budgetTokens: 5632,
          contextWindow: 8192,
          estimated: false,
          verifiedInputTokens: 4096,
          windowSource: 'endpoint',
          inputLimitSource: 'endpoint',
          tokenEstimateRatio: 1.25,
          modelName: 'custom',
        },
      },
    })
    expect(wrapper.find('.context-meter__limit').text()).toBe('上限 8K')
    const title = wrapper.find('button').attributes('title')
    expect(title).toContain('已验证输入至少 4,096 token')
    expect(title).toContain('不是最大容量')
    expect(title).toContain('×1.25')
  })
})

describe('上下文三档菜单', () => {
  it('960.6K 窗口的菜单包含当前 256K，完整档保留精确输入上限', async () => {
    const wrapper = mount(ContextMeter, {
      props: {
        usage: {
          promptTokens: 4096,
          budgetTokens: 65536,
          contextWindow: 983616,
          inputLimitTokens: 979008,
          estimated: true,
          modelName: 'large-model',
          windowSource: 'configured',
        },
      },
      global: {
        stubs: {
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElDropdownMenu: { template: '<ul><slot /></ul>' },
          ElDropdownItem: {
            props: ['command'],
            template: '<li class="preset-item" :data-budget="command"><slot /></li>',
          },
        },
      },
    })
    expect(wrapper.findAll('.preset-item').map((item) => item.text())).toEqual(['精简256K', '均衡512K', '完整956.1K'])
    expect(wrapper.findAll('.preset-item')[2].attributes('data-budget')).toBe('979008')
    expect(wrapper.find('.context-meter__current').text()).toBe('当前预算64K')
    expect(wrapper.find('.context-meter__head').text()).not.toContain('尚未验证')
    await wrapper.setProps({ usage: { ...wrapper.props('usage'), budgetTokens: 262144 } })
    expect(wrapper.find('.context-meter__value').text()).toBe('≈4K / 256K')
    expect(wrapper.find('.context-meter__current').text()).toBe('当前预算256K')
    expect(wrapper.find('.context-meter__item--on').text()).toBe('精简256K')
    await wrapper.setProps({ usage: { ...wrapper.props('usage'), budgetTokens: 979008 } })
    expect(wrapper.find('.context-meter__value').text()).toBe('≈4K / 956.1K')
    expect(wrapper.findAll('.preset-item')).toHaveLength(3)
    expect(wrapper.find('.context-meter__item--on').text()).toBe('完整956.1K')
  })
})
