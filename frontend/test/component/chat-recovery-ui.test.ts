import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ChatTurnWarnings from '@/components/chat/ChatTurnWarnings.vue'
import ChatRunNotice from '@/components/chat/ChatRunNotice.vue'
import type { ChatRun } from '@/api/chat-runs'

describe('失败后的内联交付', () => {
  it('提示默认折叠，不盖住答案或要求点击继续', async () => {
    const wrapper = mount(ChatTurnWarnings, {
      props: { busy: true, warnings: [{ code: 'AGENT_RECOVERY', message: '本轮已改为整理已有结果，未重试工具操作' }] },
    })
    expect(wrapper.get('summary').text()).toContain('1 条执行提示')
    expect(wrapper.get('summary').text()).toContain('正在继续处理')
    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.find('.el-alert').exists()).toBe(false)
    expect(wrapper.find('[role=dialog]').exists()).toBe(false)
    expect(wrapper.find('button').exists()).toBe(false)
    await wrapper.setProps({ busy: false })
    expect(wrapper.get('summary').text()).toContain('查看本轮处理说明')
    expect(wrapper.get('li').text()).toContain('未重试工具操作')
    wrapper.unmount()
  })
  it('未知写结果自动收尾后仅提示核实，不再重复要求整理结果', async () => {
    const run: ChatRun = {
      id: 'r',
      conversationId: 'c',
      status: 'INTERRUPTED',
      recoveryMode: 'SUMMARY_ONLY',
      events: [{ id: 1, name: 'warning', payload: JSON.stringify({ code: 'AGENT_RECOVERY' }) }],
    }
    const wrapper = mount(ChatRunNotice, {
      props: { run },
      global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
    })
    expect(wrapper.text()).toContain('已有结果已在上方整理')
    expect(wrapper.text()).toContain('查看活动记录')
    expect(wrapper.find('button').exists()).toBe(false)
    await wrapper.setProps({ run: { ...run, events: [...run.events, { id: 2, name: 'reset', payload: '{}' }] } })
    expect(wrapper.text()).toContain('整理已有结果')
    expect(wrapper.findAll('button')).toHaveLength(1)
    wrapper.unmount()
  })
})
