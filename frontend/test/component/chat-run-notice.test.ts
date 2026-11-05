import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatRunNotice from '@/components/chat/ChatRunNotice.vue'
import type { ChatRun } from '@/api/chat-runs'
const run: ChatRun = { id: 'r', conversationId: 'c', status: 'INTERRUPTED', events: [], recoveryMode: 'SUMMARY_ONLY' }
describe('任务恢复提示', () => {
  it('未知工具结果只提供摘要和核对入口，不能误点继续', async () => {
    const wrapper = mount(ChatRunNotice, { props: { run }, global: { stubs: { RouterLink: true } } })
    expect(wrapper.text()).toContain('工具执行结果待核实')
    expect(wrapper.text()).not.toContain('继续任务')
    expect(wrapper.getComponent({ name: 'RouterLink' }).attributes('to')).toBe('/activity')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('summarize')).toHaveLength(1)
    expect(wrapper.emitted('resume')).toBeUndefined()
    await wrapper.setProps({ busy: true })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('后台仍运行时仅重新连接，不重复提交任务', async () => {
    const wrapper = mount(ChatRunNotice, { props: { run: { ...run, status: 'RUNNING' } } })
    expect(wrapper.text()).toContain('重新连接')
    expect(wrapper.text()).not.toContain('整理已有结果')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('resume')).toHaveLength(1)
    wrapper.unmount()
  })
})
