import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import ChatExecutionTrail from '@/components/chat/ChatExecutionTrail.vue'
describe('执行过程', () => {
  it('工具进入运行态，结束后显示耗时与回答生成阶段', async () => {
    const item = { id: 'tool-1', tool: 'mcp_terminal_exec', status: 'RUNNING', durationMs: 0 }
    const wrapper = mount(ChatExecutionTrail, { props: { items: [item] } })
    expect(wrapper.get('[role="status"]').text()).toContain('执行命令')
    expect(wrapper.text()).toContain('mcp_terminal_exec')
    await wrapper.setProps({ items: [{ ...item, status: 'OK', durationMs: 1250 }], generating: true })
    expect(wrapper.text()).toContain('已完成')
    expect(wrapper.text()).toContain('1.3s')
    expect(wrapper.get('[role="status"]').text()).toBe('正在生成回答')
    wrapper.unmount()
  })
  it('重连与待确认状态不能显示为执行成功', async () => {
    const wrapper = mount(ChatExecutionTrail, {
      props: { items: [{ id: '1', tool: 'deleteTask', status: 'REQUIRES_CONFIRMATION', durationMs: 5 }] },
    })
    expect(wrapper.text()).toContain('等待你确认')
    await wrapper.setProps({ reconnecting: true })
    expect(wrapper.get('[role="status"]').text()).toContain('任务在后台继续')
    wrapper.unmount()
  })
})
