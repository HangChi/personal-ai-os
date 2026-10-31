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
    expect(wrapper.find('.context-meter__limit').text()).toContain('上限')
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
})
