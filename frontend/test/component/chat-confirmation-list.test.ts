import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ChatConfirmationList from '@/components/chat/ChatConfirmationList.vue'
import type { TaskConfirmation } from '@/types/api'
const item: TaskConfirmation = {
  id: 'confirmation-1',
  token: 'test-token',
  action: 'DELETE_CALENDAR_EVENT',
  summary: '删除面试日程及关联提醒，确认后生效。',
  affectedCount: 2,
  status: 'PENDING',
  expiresAt: '2099-11-02T00:00:00Z',
  createdAt: '2026-11-01T00:00:00Z',
}
describe('单次点击的人工确认', () => {
  it('直接展示影响，单次允许立即传递决定且不弹二次确认', async () => {
    const wrapper = mount(ChatConfirmationList, { props: { items: [item], resolvingId: null } })
    expect(wrapper.text()).toContain(item.summary)
    expect(wrapper.text()).toContain('涉及 2 项')
    expect(wrapper.text()).toContain('删除后无法撤销')
    expect(wrapper.text()).toContain('有效至')
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('resolve')).toEqual([[item, true]])
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    await wrapper.findAll('button')[1].trigger('click')
    expect(wrapper.emitted('resolve')).toHaveLength(1)
    wrapper.unmount()
  })
  it('不选择不发请求；单次拒绝传递拒绝决定', async () => {
    const wrapper = mount(ChatConfirmationList, { props: { items: [item], resolvingId: null } })
    expect(wrapper.emitted('resolve')).toBeUndefined()
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('resolve')).toEqual([[item, false]])
    wrapper.unmount()
  })
  it('处理期间、模型执行期间与过期操作禁止决定', async () => {
    const wrapper = mount(ChatConfirmationList, { props: { items: [item], resolvingId: item.id } })
    for (const button of wrapper.findAll('button')) expect(button.attributes('disabled')).toBeDefined()
    await wrapper.setProps({ resolvingId: null, busy: true })
    for (const button of wrapper.findAll('button')) expect(button.attributes('disabled')).toBeDefined()
    await wrapper.setProps({ busy: false, items: [{ ...item, expiresAt: '2000-01-01T00:00:00Z' }] })
    expect(wrapper.text()).toContain('确认已过期')
    for (const button of wrapper.findAll('button')) expect(button.attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('失败返回后可再次决定，并显示错误原因', async () => {
    const wrapper = mount(ChatConfirmationList, { props: { items: [item], resolvingId: null } })
    await wrapper.findAll('button')[1].trigger('click')
    await wrapper.setProps({ resolvingId: item.id })
    await wrapper.setProps({ resolvingId: null, error: '执行失败，请重试' })
    expect(wrapper.text()).toContain('执行失败，请重试')
    expect(wrapper.findAll('button')[1].attributes('disabled')).toBeUndefined()
    wrapper.unmount()
  })
})
